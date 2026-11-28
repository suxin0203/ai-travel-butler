package com.suxin.travel.traveljava.common;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis Lua 脚本集中管理：此前 INCR_EXPIRE 在限流拦截器与邮箱验证码服务里各有一份
 * 且行为分叉（前者有 TTL<0 自愈、后者没有）——收敛为单一事实源，统一含自愈分支。
 */
public final class RedisScripts {

    private RedisScripts() {
    }

    /**
     * INCR + EXPIRE 合并为一次原子调用：首次计数或键无 TTL（历史泄漏）时设置过期。
     * 为什么不用"INCR 后判断 count==1 再 EXPIRE"：两步之间进程崩溃会让 key 永不过期。
     * KEYS[1]=计数键，ARGV[1]=过期秒数，返回自增后的计数。
     */
    public static final DefaultRedisScript<Long> INCR_EXPIRE = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
                    "if c == 1 or redis.call('TTL', KEYS[1]) < 0 then " +
                    "redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                    "return c", Long.class);
}
