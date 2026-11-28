package com.suxin.travel.traveljava.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.common.RedisScripts;
import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 每用户每日 AI 调用限流。
 * 为什么必须做：LLM 按量计费，接口一旦裸奔，别人的脚本一晚上就能把额度烧光。
 * 实现：INCR 日计数 + EXPIRE NX（Redis 7 支持 NX 选项，只在首次设置过期时间，
 * 原子操作，避免 INCR 后进程挂掉导致 key 永不过期、用户永久 429）。
 * key 里带日期本身也是跨天兜底。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${llm.daily-limit:50}")
    private int dailyLimit;

    /**
     * INCR + EXPIRE 原子 Lua 已抽公共 RedisScripts.INCR_EXPIRE（v2.10 与邮箱验证码计数
     * 合并为单一事实源，含 TTL<0 历史泄漏键自愈分支）
     */

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // SSE 完成时容器会做一次 ASYNC 重派发，拦截器链会重跑一遍：
        // 鉴权/限流在首轮 REQUEST 已完成，ASYNC 直接放行，否则限流计数被双倍累加
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        Long uid = UserContext.get() == null ? null : UserContext.get().getUid();
        if (uid == null) {
            // 理论上进不到这里（AuthInterceptor 先行），防御一下
            return reject(response, 401, "未登录或登录已失效");
        }
        String key = RedisKeys.llmRateToday(uid);
        Long count = redis.execute(RedisScripts.INCR_EXPIRE, java.util.List.of(key),
                String.valueOf(Duration.ofDays(1).toSeconds()));
        if (count != null && count > dailyLimit) {
            // 超限的这次不回滚也无所谓：多计一次不影响限流语义
            return reject(response, 429, "今日 AI 调用次数已用完（" + dailyLimit + " 次/天），明天再来吧");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
        return false;
    }
}
