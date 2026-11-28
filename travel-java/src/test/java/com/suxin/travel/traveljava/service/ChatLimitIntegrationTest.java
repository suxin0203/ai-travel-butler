package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.entity.Conversation;
import com.suxin.travel.traveljava.entity.Message;
import com.suxin.travel.traveljava.mapper.ConversationMapper;
import com.suxin.travel.traveljava.mapper.MessageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话/轮数上限集成测试（契约 §3.1）：
 * - 会话上限（默认 10）：造 11 个会话后触发 trim，最旧的被物理删除
 * - 轮数上限（默认 20）：20 条 USER 消息后再对话返回 400，且 USER 消息在判定前不落库
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatService chatService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long UID = 601L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM user");
    }

    private long insertConversation(String title) {
        Conversation c = new Conversation();
        c.setUserId(UID);
        c.setTitle(title);
        conversationMapper.insert(c);
        return c.getId();
    }

    private void insertUserMessage(long convId, String content) {
        Message m = new Message();
        m.setConversationId(convId);
        m.setRole("USER");
        m.setContent(content);
        messageMapper.insert(m);
    }

    @Test
    @DisplayName("会话上限：11 个会话触发清理，最旧的被物理删除（含消息）")
    void trimOldest() {
        long firstId = insertConversation("最旧的会话");
        for (int i = 0; i < 10; i++) {
            insertConversation("会话" + i);
        }
        assertEquals(11, conversationMapper.selectCount(null));

        long cleaned = chatService.trimOldestConversations(UID);
        assertEquals(1, cleaned);
        assertEquals(10, conversationMapper.selectCount(null));
        // 最旧的（id 最小）连同消息一起被物理删除
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE id=?", Long.class, firstId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id=?", Long.class, firstId));
    }

    @Test
    @DisplayName("轮数上限：20 条 USER 消息后继续对话返回 400 且不落库")
    void roundLimit() throws Exception {
        // 注册并登录
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"limiter\",\"password\":\"pass123\"}"));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"limiter\",\"password\":\"pass123\"}"))
                .andReturn();
        String body = login.getResponse().getContentAsString();
        String token = body.substring(body.indexOf("\"token\":\"") + 9,
                body.indexOf("\"", body.indexOf("\"token\":\"") + 9));
        long uid = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username='limiter'", Long.class);
        // 会话归属必须是当前登录用户（uid 是自增值，不能写死），否则归属校验返回 404
        jdbcTemplate.update("INSERT INTO conversation(user_id,title) VALUES (?,?)", uid, "满轮会话");
        long convId = jdbcTemplate.queryForObject(
                "SELECT id FROM conversation WHERE user_id=? AND title='满轮会话'", Long.class, uid);
        for (int i = 0; i < 20; i++) {
            insertUserMessage(convId, "第" + i + "轮");
        }

        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":" + convId + ",\"message\":\"继续聊\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("本会话已达 20 轮对话上限，请新建会话继续（本次调用配额已消耗）"));

        // 判定发生在 USER 消息落库之前：仍然是 20 条
        assertEquals(20, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id=?", Long.class, convId));
        assertTrue(uid > 0);
    }
}
