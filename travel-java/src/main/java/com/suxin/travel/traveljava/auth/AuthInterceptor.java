package com.suxin.travel.traveljava.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.common.JwtUtil;
import com.suxin.travel.traveljava.common.RedisKeys;
import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 登录态拦截器：验签 JWT + 查 Redis 白名单。
 * 为什么要查 Redis：JWT 验签只能证明"这个 token 是我签的、没过期"，
 * 不能证明"它还没被登出/强制下线"。白名单里删掉 key，token 立即失效——
 * 这就是"JWT 无状态 + 业务可控下线"的实现点。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // SSE 完成时容器会做一次 ASYNC 重派发，拦截器链会重跑一遍：
        // 鉴权/限流在首轮 REQUEST 已完成，ASYNC 直接放行，否则限流计数被双倍累加
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(response);
        }
        JwtUtil.ParsedToken parsed = jwtUtil.parse(auth.substring(7));
        if (parsed == null) {
            return reject(response);
        }
        String key = RedisKeys.loginToken(parsed.uid(), parsed.jti());
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            // 签名有效但白名单里没有：说明已登出或被强制下线
            return reject(response);
        }
        // 滑动续期：活跃用户的登录态随请求刷新，避免 7 天后突然掉线
        redis.expire(key, Duration.ofHours(jwtUtil.expireHours()));
        UserContext.set(new UserContext.Principal(parsed.uid(), parsed.username(), parsed.jti()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        // 线程复用场景必须清理，否则下个请求可能读到上个用户的身份
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(401, "未登录或登录已失效")));
        return false;
    }
}
