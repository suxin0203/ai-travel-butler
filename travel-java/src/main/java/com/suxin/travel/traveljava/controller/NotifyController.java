package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.common.SseConstants;
import com.suxin.travel.traveljava.notify.SseRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 提醒长连接（契约 §4.4）。
 * 鉴权走 AuthInterceptor（Authorization 头，前端 fetch 封装，不用 EventSource）；
 * M1 已修复的 ASYNC 重派发守卫在这里至关重要——本连接每次推送/心跳都可能触发异步事件，
 * 若拦截器重跑，每次都会白白走一遍 Redis 白名单查询。
 */
@RestController
@RequiredArgsConstructor
public class NotifyController {

    private final SseRegistry sseRegistry;

    @GetMapping(value = "/api/notify/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        long uid = UserContext.requireUid();
        // 30 分钟上限：半开 TCP 连接靠 onTimeout 回收，避免 send 永久阻塞占资源；
        // 前端契约要求自动重连（指数退避），到时重连即可
        SseEmitter emitter = new SseEmitter(SseConstants.TIMEOUT_MS);
        sseRegistry.register(uid, emitter);
        return emitter;
    }
}
