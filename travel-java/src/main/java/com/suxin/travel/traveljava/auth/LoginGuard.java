package com.suxin.travel.traveljava.auth;

import com.suxin.travel.traveljava.common.BusinessException;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.common.RedisScripts;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 登录防爆破（契约 §2.2 v2.10）：IP+用户名 维度，从第一次失败起算的 10 分钟固定窗口内
 * 累计 5 次失败后锁定该组合直至窗口过期（锁定期间不再计数，实际防护强度≈5 次/10 分钟），
 * 登录成功即清零计数。
 *
 * 为什么放 service 而不是拦截器：登录参数是 JSON body，拦截器 preHandle 阶段
 * 读不到（读了会污染 @RequestBody），afterCompletion 再解析又多一层 body 缓存
 * 包装——失败/成功都在 AuthService.login 里发生，直接就地调用本组件最内聚。
 *
 * IP 取 X-Real-IP 而非 X-Forwarded-For（后者 nginx 默认 $proxy_add_x_forwarded_for
 * 是追加模式，第一段可被客户端伪造）。**前提**：前置反代必须用覆盖式
 * proxy_set_header X-Real-IP $remote_addr（宝塔默认模板已含；若为透传式则 IP 维度
 * 可被伪造——发版前按待办 §5a 核实生产站点 conf）。本地直连无头时退回 remoteAddr。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginGuard {

    /** 10 分钟滚动窗口内允许的最大失败次数（第 6 次起 429） */
    static final int MAX_FAILS = 5;
    /** 滚动窗口时长：从第一次失败起算，窗口内每次失败都会刷新过期时间 */
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    /** 登录前置检查：该 IP+用户名 处于锁定窗口则直接 429（契约 §1.3 限流形态） */
    public void checkLocked(String ip, String username) {
        String count = redis.opsForValue().get(RedisKeys.loginFail(ip, username));
        if (count != null && Integer.parseInt(count) >= MAX_FAILS) {
            log.info("登录锁定命中 ip={} username={}", ip, username);
            throw BusinessException.tooManyRequests("尝试次数过多，请 10 分钟后再试");
        }
    }

    /** 登录失败：计数 +1 并刷新窗口（原子 Lua，含无 TTL 历史键自愈） */
    public void recordFailure(String ip, String username) {
        redis.execute(RedisScripts.INCR_EXPIRE,
                java.util.List.of(RedisKeys.loginFail(ip, username)),
                String.valueOf(WINDOW.toSeconds()));
    }

    /** 登录成功：清零（正确密码随时能解锁，不因历史失败被永久拒绝） */
    public void reset(String ip, String username) {
        redis.delete(RedisKeys.loginFail(ip, username));
    }

    /** 从当前请求上下文取客户端真实 IP（无请求上下文时返回 unknown，测试/定时路径防御） */
    public static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
            return req.getRemoteAddr();
        }
        return "unknown";
    }
}
