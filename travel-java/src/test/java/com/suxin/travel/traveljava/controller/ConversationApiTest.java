package com.suxin.travel.traveljava.controller;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 会话列表/历史消息接口测试：归属校验（404）、limit 参数、消息只回可回放类型 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM user");
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"}"));
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"}"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int i = body.indexOf("\"token\":\"") + "\"token\":\"".length();
        return body.substring(i, body.indexOf("\"", i));
    }

    private long uidOf(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM user WHERE username=?", Long.class, username);
    }

    @Test
    @DisplayName("会话列表只返回本人的；他人会话历史 404")
    void ownershipOnConversations() throws Exception {
        String tokenA = registerAndLogin("alice");
        String tokenB = registerAndLogin("bob");
        long uidA = uidOf("alice");

        jdbcTemplate.update("INSERT INTO conversation(user_id,title) VALUES (?,?)", uidA, "A的会话");

        // A 能看到自己的会话
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("A的会话"));

        // B 列表为空
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // conv id = 1（自增），B 访问 A 的历史 → 404
        mockMvc.perform(get("/api/conversations/1/messages").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("历史消息只回 USER 与最终 ASSISTANT，工具轨迹不回传")
    void messagesFilterToolTrace() throws Exception {
        String token = registerAndLogin("carol");
        long uid = uidOf("carol");
        jdbcTemplate.update("INSERT INTO conversation(user_id,title) VALUES (?,?)", uid, "C的会话");
        long convId = jdbcTemplate.queryForObject(
                "SELECT id FROM conversation WHERE user_id=?", Long.class, uid);
        jdbcTemplate.update("INSERT INTO message(conversation_id,role,content) VALUES (?,?,?)",
                convId, "USER", "你好");
        jdbcTemplate.update("INSERT INTO message(conversation_id,role,content,tool_calls_json) VALUES (?,?,?,?)",
                convId, "ASSISTANT", "", "{\"tool\":\"call\"}");
        jdbcTemplate.update("INSERT INTO message(conversation_id,role,content) VALUES (?,?,?)",
                convId, "TOOL", "{\"ok\":true}");
        jdbcTemplate.update("INSERT INTO message(conversation_id,role,content) VALUES (?,?,?)",
                convId, "ASSISTANT", "你好呀，想去哪玩？");

        mockMvc.perform(get("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].content").value("你好"))
                .andExpect(jsonPath("$.data[1].content").value("你好呀，想去哪玩？"));
    }

    @Test
    @DisplayName("删除会话：物理删除含消息；他人/不存在/重复删除 404（契约 §3.2a）")
    void deleteConversation() throws Exception {
        String tokenA = registerAndLogin("alice");
        long uidA = uidOf("alice");
        jdbcTemplate.update("INSERT INTO conversation(user_id,title) VALUES (?,?)", uidA, "待删会话");
        long convId = jdbcTemplate.queryForObject(
                "SELECT id FROM conversation WHERE user_id=?", Long.class, uidA);
        jdbcTemplate.update("INSERT INTO message(conversation_id,role,content) VALUES (?,?,?)",
                convId, "USER", "你好");

        // 他人删除 → 404（防探测，与非本人读历史一致）
        String tokenB = registerAndLogin("bob");
        mockMvc.perform(delete("/api/conversations/" + convId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // 本人删除 → 200 {deleted:true}，会话与消息一并物理删除
        mockMvc.perform(delete("/api/conversations/" + convId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE id=?", Long.class, convId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id=?", Long.class, convId));

        // 历史消息接口 → 404；重复删除同一会话 → 404（物理删后与「从未存在」不可区分）
        mockMvc.perform(get("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/conversations/" + convId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}
