package com.suxin.travel.traveljava.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前登录用户上下文（ThreadLocal）。
 * 由 AuthInterceptor 在请求进入时写入，请求结束（afterCompletion）必须清理，
 * 否则线程池复用线程会把上一个用户身份带给下一个请求——越权事故的典型来源。
 */
public class UserContext {
    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    @Data
    @AllArgsConstructor
    public static class Principal {
        private Long uid;
        private String username;
        /** 当前 token 的 jti，登出/强制下线时按它删 Redis 键 */
        private String jti;
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    /** 未登录场景不该调用此方法；防御式报错，把问题尽早暴露 */
    public static Long requireUid() {
        Principal p = HOLDER.get();
        if (p == null) {
            throw new IllegalStateException("UserContext 未初始化，请检查拦截器配置");
        }
        return p.getUid();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
