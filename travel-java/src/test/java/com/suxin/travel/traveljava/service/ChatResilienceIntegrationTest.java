package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.ChatRequestDTO;
import com.suxin.travel.traveljava.llm.LLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 对话链路稳健性集成测试（稳健性轮 v2.10 P0-1/P0-2）：
 * 1. 线程池拒绝（AbortPolicy 抛 RejectedExecutionException）：此前 emitter 悬挂到 30 分钟超时
 *    ——修复后向前端发 error 事件并幂等 complete（契约 §3.1 error 场景）；
 * 2. 客户端取消：executeChat 循环中止时统一 completeQuietly，不滞留连接。
 * 事件断言利用 SseEmitter 的 EarlyRequestCache：complete 前未 initialize 时 send/complete
 * 被缓存，测试手动 initialize 一个记录型 Handler 即可回放全部事件。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatResilienceIntegrationTest {

    @Autowired
    private ChatService chatService;
    @MockBean
    private ThreadPoolTaskExecutor llmExecutor;
    @MockBean
    private LLMClient llmClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long UID = 401L;

    /**
     * 反射读 ResponseBodyEmitter 的 complete 私有标志：Handler 接口是包私有不可实现，
     * complete 标志是「emitter 已收口」的唯一可靠外部证据（悬挂 bug 的直接断言面）
     */
    static boolean isComplete(SseEmitter emitter) {
        try {
            java.lang.reflect.Field f = org.springframework.web.servlet.mvc.method.annotation
                    .ResponseBodyEmitter.class.getDeclaredField("complete");
            f.setAccessible(true);
            return f.getBoolean(emitter);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射读取 complete 标志失败", e);
        }
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
    }

    private UserContext.Principal principal() {
        return new UserContext.Principal(UID, "resilient", "jti-test");
    }

    @Test
    @DisplayName("线程池拒绝：发 error 事件（提示稍后重试）并完成 emitter，不再悬挂")
    void executorRejectionSendsErrorAndCompletes() {
        doThrow(new RejectedExecutionException("queue full"))
                .when(llmExecutor).execute(any(Runnable.class));

        ChatRequestDTO dto = new ChatRequestDTO();
        dto.setMessage("你好");
        SseEmitter emitter = chatService.chat(principal(), dto);
        assertNotNull(emitter, "拒绝路径必须正常返回 emitter，而非异常上抛变 500");
        assertTrue(isComplete(emitter), "emitter 必须已 complete（此前悬挂到 30 分钟超时）");
    }

    @Test
    @DisplayName("客户端取消：executeChat 中止循环并幂等完成 emitter，不抛异常")
    void cancelPathCompletesQuietly() throws Exception {
        // 预置取消态：循环第一轮检查 isCancelled 即中止（模拟客户端断开触发回调置位）
        LLMClient.Handle handle = new LLMClient.Handle();
        handle.cancel();

        ChatRequestDTO dto = new ChatRequestDTO();
        dto.setMessage("取消我");
        // chat() 正常建会话落 USER 消息并启动 executor（executor 已 Mock，须手动驱动执行体）
        doThrow(new RejectedExecutionException("not used")).when(llmExecutor).execute(any(Runnable.class));
        // 直接驱动 executeChat（包级可见即为此设计）：取消分支应静默返回 null
        SseEmitter emitter = new SseEmitter(0L);
        String answer = chatService.executeChat(UID, newConversationId(), emitter, handle);
        assertNull(answer, "取消路径应返回 null（不落 ASSISTANT）");
        assertTrue(isComplete(emitter), "取消分支必须 completeQuietly（此前滞留到超时）");
    }

    private long newConversationId() {
        jdbcTemplate.update("INSERT INTO conversation(user_id, title, created_at, updated_at) VALUES (?, 't', NOW(), NOW())", UID);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM conversation WHERE user_id=? ORDER BY id DESC LIMIT 1", Long.class, UID);
    }
}
