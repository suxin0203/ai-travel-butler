package com.suxin.travel.traveljava.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 在线注册表：userId → 该用户当前打开的提醒长连接集合。
 * 用 Set 而不是单值 Map：同一用户开多个标签页是常态，后者不能覆盖前者；
 * 每个连接的移除全部挂在 onCompletion/onTimeout/onError 回调上，防止内存泄漏。
 * 心跳（30s 一次注释行）由本类的 @Scheduled 统一广播，写失败即认为连接已死并移除。
 */
@Slf4j
@Component
public class SseRegistry {

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SseRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(long uid, SseEmitter emitter) {
        emitter.onCompletion(() -> unregister(uid, emitter));
        emitter.onTimeout(() -> unregister(uid, emitter));
        emitter.onError(t -> unregister(uid, emitter));
        emitters.computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        log.info("提醒长连接注册 uid={} 当前在线连接数={}", uid, totalCount());
    }

    public void unregister(long uid, SseEmitter emitter) {
        // compute 把"移除+判空删 key"合并成一次原子操作：
        // 否则 check-then-act 竞态会把并发新注册的连接连同整个 set 一起误删
        emitters.compute(uid, (k, set) -> {
            if (set == null) {
                return null;
            }
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        log.debug("提醒长连接移除 uid={} 剩余连接数={}", uid, totalCount());
    }

    /** 用户是否在线（有至少一条活跃提醒长连接）——渠道可用性判定依据（契约 §4.6） */
    public boolean isOnline(long uid) {
        Set<SseEmitter> set = emitters.get(uid);
        return set != null && !set.isEmpty();
    }

    /** 推送给某用户全部在线连接；至少一条写成功即认为送达（推送即送达语义，契约 §4.4） */
    public boolean push(long uid, Object payload) {
        Set<SseEmitter> set = emitters.get(uid);
        if (set == null || set.isEmpty()) {
            return false;
        }
        boolean any = false;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("提醒事件序列化失败", e);
            return false;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().data(json));
                any = true;
            } catch (Exception e) {
                // 写失败说明连接已死，移除；complete 触发回调兜底二次移除
                unregister(uid, emitter);
            }
        }
        return any;
    }

    private int totalCount() {
        return emitters.values().stream().mapToInt(Set::size).sum();
    }

    /** 30s 心跳：契约 §1.6/§4.4；任何写失败（连接已死/emitter 已完成）即移除 */
    @Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        emitters.forEach((uid, set) -> {
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment(" ping"));
                } catch (Exception e) {
                    // 半开连接上 send 可能阻塞或抛 IllegalStateException，统一按死连接处理
                    unregister(uid, emitter);
                }
            }
        });
    }

    /** 供测试断言在线连接数 */
    int totalCountForTest() {
        return totalCount();
    }

    /** 生成事件 id：前端幂等去重用（契约 §1.6） */
    public static String newEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
