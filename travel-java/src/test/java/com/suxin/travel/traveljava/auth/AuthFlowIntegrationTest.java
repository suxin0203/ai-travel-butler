package com.suxin.travel.traveljava.auth;

import com.suxin.travel.traveljava.common.JwtUtil;
import com.suxin.travel.traveljava.common.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证全流程集成测试（真实 MySQL ai_travel_test 库 + 真实 Redis db4）。
 * 覆盖：注册/重名/参数校验、登录/错误密码、鉴权 401、登出、强制下线、改邮箱。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    // 发码链路的真实 SMTP 由 mock 吞掉（测试不触网；渠道判定所需的 bean 仍存在）
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.mail.javamail.JavaMailSender mailSender;

    @BeforeEach
    @AfterEach
    void cleanDb() {
        // 每个用例前后清表，保证用例之间互不污染（只清测试库 ai_travel_test）
        jdbcTemplate.update("DELETE FROM user");
        flushRedis();
    }

    /** 清空测试专用 Redis 库 db4（等价于 FLUSHDB，走 Redis 连接而非 JDBC） */
    private void flushRedis() {
        redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private String registerBody() {
        return "{\"username\":\"suxin\",\"password\":\"pass123\",\"email\":\"a@b.com\"}";
    }

    @Test
    @DisplayName("注册成功返回 userId；重复注册 400")
    void registerThenDuplicate() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("suxin"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    @DisplayName("参数校验失败返回 400 与字段提示")
    void registerValidation() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("注册：邮箱留空(前端表单提交空串)视同未填，成功注册（生产部署实测回归）")
    void registerBlankEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"blank_email_user\",\"password\":\"pass123\",\"email\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("blank_email_user"));
    }

    @Test
    @DisplayName("登录：错误密码 400 且文案统一；正确密码返回 token")
    void loginFlow() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody()));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"suxin\",\"password\":\"wrong!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"suxin\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInHours").value(1))
                .andReturn();
        assertEquals(200, login.getResponse().getStatus());
        // 白名单 TTL 必须是"秒级剩余有效期"（修复过 once 误用 ofHours 导致 69 年不过期的 bug）
        JwtUtil.ParsedToken parsed = jwtUtil.parse(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(login.getResponse().getContentAsString())
                        .path("data").path("token").asText());
        Long ttl = redis.getExpire(RedisKeys.loginToken(parsed.uid(), parsed.jti()));
        assertTrue(ttl != null && ttl > 0 && ttl <= 3600, "白名单 TTL 应在 1 小时内，实际=" + ttl);
    }

    @Test
    @DisplayName("me 需要登录：无 token 401；带 token 返回配额信息")
    void meAuth() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String token = loginAndGetToken();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("suxin"))
                .andExpect(jsonPath("$.data.quota.used").value(0))
                .andExpect(jsonPath("$.data.quota.limit").value(3));
    }

    @Test
    @DisplayName("登出后原 token 立即 401")
    void logoutInvalidates() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("强制下线：直接删 Redis 登录态，token 立即失效（管理端踢人场景）")
    void forcedLogout() throws Exception {
        String token = loginAndGetToken();
        // 模拟管理端：解析 token 拿 uid/jti，删掉白名单 key
        JwtUtil.ParsedToken parsed = jwtUtil.parse(token);
        redis.delete(RedisKeys.loginToken(parsed.uid(), parsed.jti()));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("改邮箱：验证码绑定成功；占用邮箱正确码仍 400 且作废验证码（契约 v2.4）")
    void updateEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"other\",\"password\":\"pass123\",\"email\":\"taken@b.com\"}"));
        String token = loginAndGetToken();

        // 契约 v2.4：绑定新邮箱需验证码——发码 → 从 Redis 取码 → 带码绑定成功
        mockMvc.perform(post("/api/auth/email/code").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@b.com\"}"))
                .andExpect(status().isOk());
        String code = redis.opsForValue().get(RedisKeys.emailBindCode("new@b.com"));
        mockMvc.perform(put("/api/auth/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@b.com\",\"emailCode\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new@b.com"));

        // 绑定被占用邮箱：正确码命中后仍因唯一性冲突 400（并按契约作废验证码）
        mockMvc.perform(post("/api/auth/email/code").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"taken@b.com\"}"))
                .andExpect(status().isOk());
        String takenCode = redis.opsForValue().get(RedisKeys.emailBindCode("taken@b.com"));
        mockMvc.perform(put("/api/auth/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@b.com\",\"emailCode\":\"" + takenCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private String loginAndGetToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody()));
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"suxin\",\"password\":\"pass123\"}"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int i = body.indexOf("\"token\":\"") + "\"token\":\"".length();
        return body.substring(i, body.indexOf("\"", i));
    }
}
