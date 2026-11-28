package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.ChatRequestDTO;
import com.suxin.travel.traveljava.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话接口（API 契约 §3.1）：放行后返回 SSE 流，被拦截时返回 JSON（契约 §3.0 双形态） */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequestDTO dto) {
        return chatService.chat(UserContext.get(), dto);
    }
}
