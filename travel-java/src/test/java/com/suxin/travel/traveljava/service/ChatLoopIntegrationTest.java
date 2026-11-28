package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.entity.Conversation;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.llm.LLMClient;
import com.suxin.travel.traveljava.mapper.ConversationMapper;
import com.suxin.travel.traveljava.mapper.MessageMapper;
import com.suxin.travel.traveljava.mapper.ScheduleItemMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 对话工具循环集成测试：用 @MockBean 假 LLM（不真调大模型），真实走
 * "工具轮 → 执行 create_schedule → 回填结果 → 最终回答" 的完整循环。
 * 验证：日程真实创建、消息轨迹落库（USER/ASSISTANT+toolcalls/TOOL/ASSISTANT）、
 * 历史回放只含 USER 与最终 ASSISTANT。
 * 注意：executeChat 不再重复接收 userMessage——当前 USER 消息由 chat() 落库、
 * 历史回放自动带入上下文，测试须先 saveUserMessage 模拟真实路径。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatLoopIntegrationTest {

    @Autowired
    private ChatService chatService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private ScheduleItemMapper scheduleItemMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private LLMClient llmClient;

    private static final long UID = 301L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM schedule_item");
    }

    /** executeChat 不负责 USER 落库（真实链路在 chat() 里），直调测试须先补齐以模拟真实路径 */
    private void saveUserMessage(long convId, String content) {
        com.suxin.travel.traveljava.entity.Message m = new com.suxin.travel.traveljava.entity.Message();
        m.setConversationId(convId);
        m.setRole("USER");
        m.setContent(content);
        messageMapper.insert(m);
    }

    private Conversation newConversation() {
        Conversation conv = new Conversation();
        conv.setUserId(UID);
        conv.setTitle("测试会话");
        conversationMapper.insert(conv);
        return conv;
    }

    @Test
    @DisplayName("工具循环：第一轮发起 create_schedule，第二轮给出最终回答，日程真实创建")
    void toolLoopCreatesSchedule() throws Exception {
        Conversation conv = newConversation();

        // 第一次调用（工具轮）：返回 create_schedule 请求；第二次：返回最终文本
        LLMClient.StreamResult toolRound = new LLMClient.StreamResult("",
                "tool_calls",
                List.of(new LLMClient.ToolCallReq("call_1", "create_schedule",
                        "{\"title\":\"故宫\",\"start_time\":\"2026-11-26 09:00:00\",\"remind_minutes_before\":60}")));
        LLMClient.StreamResult finalRound = new LLMClient.StreamResult("已为您创建故宫日程", "stop", List.of());
        when(llmClient.chatStream(anyList(), anyList(), any(), any())).thenReturn(toolRound, finalRound);

        saveUserMessage(conv.getId(), "帮我创建明天去故宫的日程");
        SseEmitter emitter = new SseEmitter(0L);
        String answer = chatService.executeChat(UID, conv.getId(), emitter, new LLMClient.Handle());

        assertEquals("已为您创建故宫日程", answer);

        // 日程真实创建：remind_at 派生正确，source=AI
        ScheduleItem item = scheduleItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ScheduleItem>lambdaQuery()
                        .eq(ScheduleItem::getUserId, UID)).get(0);
        assertEquals("故宫", item.getTitle());
        assertEquals("AI", item.getSource());
        assertEquals("PENDING", item.getStatus());
        assertEquals("2026-11-26 08:00:00", item.getRemindAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // 消息轨迹：USER / ASSISTANT(toolcalls) / TOOL / ASSISTANT(最终)
        var messages = messageMapper.selectList(null);
        assertEquals(4, messages.size());
        assertEquals("USER", messages.get(0).getRole());
        assertEquals("ASSISTANT", messages.get(1).getRole());
        assertNotNull(messages.get(1).getToolCallsJson(), "工具轮 ASSISTANT 必须带 tool_calls_json");
        assertEquals("TOOL", messages.get(2).getRole());
        assertEquals("ASSISTANT", messages.get(3).getRole());
        assertNull(messages.get(3).getToolCallsJson(), "最终回答不带 tool_calls_json");

        // 历史回放：只有 USER + 最终 ASSISTANT（2 条），工具轨迹不回放
        List<com.suxin.travel.traveljava.entity.Message> replay = chatService.replayHistory(conv.getId(), 10);
        assertEquals(2, replay.size());
        assertEquals("USER", replay.get(0).getRole());
        assertEquals("ASSISTANT", replay.get(1).getRole());
    }

    @Test
    @DisplayName("无工具直接回答：只落 USER 与 ASSISTANT 两条")
    void plainChat() throws Exception {
        Conversation conv = newConversation();
        LLMClient.StreamResult only = new LLMClient.StreamResult("北京秋天最适合出游", "stop", List.of());
        when(llmClient.chatStream(anyList(), anyList(), any(), any())).thenReturn(only);

        saveUserMessage(conv.getId(), "北京什么时候适合旅游？");
        chatService.executeChat(UID, conv.getId(), new SseEmitter(0L), new LLMClient.Handle());

        var messages = messageMapper.selectList(null);
        assertEquals(2, messages.size());
        assertEquals(0, scheduleItemMapper.selectCount(null), "纯聊天不应创建日程");
    }

    @Test
    @DisplayName("工具执行失败以 ok:false 回给模型，循环不中断")
    void toolFailureDoesNotBreakLoop() throws Exception {
        Conversation conv = newConversation();
        // 参数故意错误：start_time 格式非法 → 工具返回 ok:false；第二轮模型照常给文本
        LLMClient.StreamResult badTool = new LLMClient.StreamResult("", "tool_calls",
                List.of(new LLMClient.ToolCallReq("call_x", "create_schedule",
                        "{\"title\":\"X\",\"start_time\":\"明天上午\"}")));
        LLMClient.StreamResult finalRound = new LLMClient.StreamResult("时间格式不对，请说明具体日期", "stop", List.of());
        when(llmClient.chatStream(anyList(), anyList(), any(), any())).thenReturn(badTool, finalRound);

        saveUserMessage(conv.getId(), "创建个日程");
        String answer = chatService.executeChat(UID, conv.getId(), new SseEmitter(0L), new LLMClient.Handle());
        assertEquals("时间格式不对，请说明具体日期", answer);
        assertEquals(0, scheduleItemMapper.selectCount(null), "非法参数不应创建日程");
        assertEquals(4, messageMapper.selectList(null).size(), "循环未中断，轨迹完整");
    }

    @Test
    @DisplayName("多轮会话：第二轮回放包含第一轮的 USER 与最终 ASSISTANT")
    void secondRoundReplaysHistory() throws Exception {
        Conversation conv = newConversation();
        // 预置第一轮的消息
        com.suxin.travel.traveljava.entity.Message user1 = new com.suxin.travel.traveljava.entity.Message();
        user1.setConversationId(conv.getId());
        user1.setRole("USER");
        user1.setContent("北京有什么好玩的？");
        messageMapper.insert(user1);
        com.suxin.travel.traveljava.entity.Message assistant1 = new com.suxin.travel.traveljava.entity.Message();
        assistant1.setConversationId(conv.getId());
        assistant1.setRole("ASSISTANT");
        assistant1.setContent("故宫、长城都值得一去");
        messageMapper.insert(assistant1);

        // 捕获第二轮回放：用任意参数 mock，从 buildMessages 无法直接观察，
        // 改为直接验证 replayHistory 的行为
        var replay = chatService.replayHistory(conv.getId(), 10);
        assertEquals(2, replay.size());
        assertEquals("北京有什么好玩的？", replay.get(0).getContent());
        assertEquals("故宫、长城都值得一去", replay.get(1).getContent());

        // 回放窗口限制生效：再插 12 条旧消息，只回最近 10 条
        for (int i = 0; i < 12; i++) {
            com.suxin.travel.traveljava.entity.Message filler = new com.suxin.travel.traveljava.entity.Message();
            filler.setConversationId(conv.getId());
            filler.setRole("USER");
            filler.setContent("填充消息" + i);
            messageMapper.insert(filler);
        }
        assertEquals(10, chatService.replayHistory(conv.getId(), 10).size());
    }
}
