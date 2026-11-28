package com.suxin.travel.traveljava.service;

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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱绑定验证码集成测试（契约 §2.5/§2.5a）。
 * 覆盖：发送成功/60s 冷却/同邮箱每日闸（双用户语义：③先于②耗尽）、缺验证码 400、
 * 错码计数与 5 次作废、键不存在不计次、归一（大写提交存小写）、唯一性冲突作废验证码、
 * 未变更幂等、无邮箱用户空 email 幂等（P1 回归）、发送响应不泄漏验证码、渠道未启用 400。
 * 真实 SMTP 由 @MockBean 吞掉；验证码断言直接读 Redis db4。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailCodeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EmailCodeService emailCodeService;
    @org.springframework.boot.test.mock.mockito.MockBean
    private JavaMailSender mailSender;

    private static final String EMAIL = "code@test.com";

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'mailer%'");
        // 邮箱验证码相关键全量清理（含每日计数键，杜绝按 uid 清理漏删的泄漏）
        redis.delete(redis.keys("email:*"));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"}"));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.substring(body.indexOf("\"token\":\"") + 9,
                body.indexOf("\"", body.indexOf("\"token\":\"") + 9));
    }

    private String sendCode(String token, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/email/code")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult putMe(String token, String email, String code) throws Exception {
        String payload = "{\"email\":\"" + email + "\"" + (code == null ? "" : ",\"emailCode\":\"" + code + "\"") + "}";
        return mockMvc.perform(put("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andReturn();
    }

    @Test
    @DisplayName("发送验证码：200 + expireSeconds=600，Redis 落 6 位码，响应不泄漏验证码")
    void sendCodeOk() throws Exception {
        String token = registerAndLogin("mailer1");
        String body = sendCode(token, EMAIL);
        assertTrue(body.contains("\"expireSeconds\":600"), body);
        String code = redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL));
        assertNotNull(code);
        assertEquals(6, code.length());
        assertFalse(body.contains(code), "响应体不得回显验证码");
    }

    @Test
    @DisplayName("冷却：60 秒内重发 429")
    void cooldownRejects() throws Exception {
        String token = registerAndLogin("mailer1");
        sendCode(token, EMAIL);
        String body = sendCode(token, EMAIL);
        assertTrue(body.contains("发送太频繁"), body);
    }

    @Test
    @DisplayName("同邮箱每日闸：双用户合计发满 10 次后第 11 次 429，且是邮箱闸而非用户闸")
    void emailDailyGate() throws Exception {
        String tokenA = registerAndLogin("mailer1");
        String tokenB = registerAndLogin("mailer2");
        // A 发 5 次、B 发 5 次（每次清 60s 冷却键模拟时间流逝；用户闸各自 ≤10 不会被触发）
        for (int i = 0; i < 5; i++) {
            redis.delete(RedisKeys.emailCodeCooldown(EMAIL));
            sendCode(tokenA, EMAIL);
            redis.delete(RedisKeys.emailCodeCooldown(EMAIL));
            sendCode(tokenB, EMAIL);
        }
        assertEquals("10", redis.opsForValue().get(RedisKeys.emailCodeDayCount(EMAIL, LocalDate.now())));
        // B 的第 6 次：邮箱计数 10→11 超限；此时 B 的用户计数仅 6——429 文案应指向邮箱闸
        redis.delete(RedisKeys.emailCodeCooldown(EMAIL));
        String body = sendCode(tokenB, EMAIL);
        assertTrue(body.contains("该邮箱今日发送次数已达上限"), body);
    }

    @Test
    @DisplayName("邮箱变更但缺验证码 → 400 请先获取邮箱验证码")
    void changeWithoutCodeRejected() throws Exception {
        String token = registerAndLogin("mailer1");
        MvcResult result = putMe(token, EMAIL, null);
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("请先获取邮箱验证码"));
    }

    @Test
    @DisplayName("验证码错误：400 且失败计数 +1；键不存在（过期）不计次")
    void wrongCodeCountsFailOnlyWhenCodeExists() throws Exception {
        String token = registerAndLogin("mailer1");
        // 场景 A：未发过码（键不存在）→ 错误但不建失败键
        MvcResult r1 = putMe(token, EMAIL, "000000");
        assertEquals(400, r1.getResponse().getStatus());
        assertNull(redis.opsForValue().get(RedisKeys.emailBindCodeFail(EMAIL)));
        // 场景 B：发了码 → 错误且失败计数 +1
        sendCode(token, EMAIL);
        MvcResult r2 = putMe(token, EMAIL, "999999");
        assertEquals(400, r2.getResponse().getStatus());
        assertTrue(r2.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("验证码错误或已过期"));
        assertEquals("1", redis.opsForValue().get(RedisKeys.emailBindCodeFail(EMAIL)));
    }

    @Test
    @DisplayName("连错 5 次：验证码作废（两键删除）且提示重新获取")
    void fiveFailsInvalidateCode() throws Exception {
        String token = registerAndLogin("mailer1");
        sendCode(token, EMAIL);
        String last = "";
        for (int i = 0; i < 5; i++) {
            last = putMe(token, EMAIL, "111111").getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
        assertTrue(last.contains("验证码错误次数过多"), last);
        assertNull(redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL)));
        assertNull(redis.opsForValue().get(RedisKeys.emailBindCodeFail(EMAIL)));
    }

    @Test
    @DisplayName("归一：大写提交绑定成功且 DB 存小写；成功后清键；未变更幂等免验证码")
    void normalizedBindAndIdempotent() throws Exception {
        String token = registerAndLogin("mailer1");
        sendCode(token, EMAIL);
        // 提交大写形式 + 正确码：应命中小写键并成功
        String code = redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL));
        MvcResult ok = putMe(token, "CODE@TEST.COM", code);
        assertEquals(200, ok.getResponse().getStatus());
        assertTrue(ok.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("code@test.com"));
        String stored = jdbcTemplate.queryForObject(
                "SELECT email FROM user WHERE username='mailer1'", String.class);
        assertEquals(EMAIL, stored);
        assertNull(redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL)));
        // 未变更：同邮箱不带验证码 → 幂等 200
        MvcResult again = putMe(token, EMAIL, null);
        assertEquals(200, again.getResponse().getStatus());
    }

    @Test
    @DisplayName("无邮箱用户空 email 幂等：200 且 data.email 为 null（P1 回归）")
    void nullEmailIdempotent() throws Exception {
        // 注册时不填邮箱 → user.email 为 NULL → PUT 空 email 走「不修改」路径，不得 500
        String token = registerAndLogin("mailer1");
        mockMvc.perform(put("/api/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(nullValue()));
    }

    @Test
    @DisplayName("唯一性冲突：正确码也 400 且验证码被作废（重放同码 → 已过期）")
    void duplicateEmailInvalidatesCode() throws Exception {
        // mailer1 先占用 code@test.com
        String token1 = registerAndLogin("mailer1");
        sendCode(token1, EMAIL);
        String code1 = redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL));
        putMe(token1, EMAIL, code1);
        // mailer2 再绑同一邮箱：发码 → 正确码 → 冲突 400 → 验证码作废
        // （先清 60s 冷却键模拟时间流逝——同邮箱频控行为已由 cooldownRejects 用例单独验证）
        redis.delete(RedisKeys.emailCodeCooldown(EMAIL));
        String token2 = registerAndLogin("mailer2");
        sendCode(token2, EMAIL);
        String code2 = redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL));
        assertNotNull(code2);
        MvcResult conflict = putMe(token2, EMAIL, code2);
        assertEquals(400, conflict.getResponse().getStatus());
        assertTrue(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("该邮箱已被其他账号绑定"));
        assertNull(redis.opsForValue().get(RedisKeys.emailBindCode(EMAIL)));
        // 同码重放 → 已过期（作废生效）
        MvcResult replay = putMe(token2, EMAIL, code2);
        assertTrue(replay.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("验证码错误或已过期"));
    }

    @Test
    @DisplayName("渠道未启用 → 400 邮件服务未启用")
    void disabledChannelRejected() throws Exception {
        String token = registerAndLogin("mailer1");
        ReflectionTestUtils.setField(emailCodeService, "mailEnabled", false);
        try {
            String body = sendCode(token, EMAIL);
            assertTrue(body.contains("邮件服务未启用"), body);
        } finally {
            ReflectionTestUtils.setField(emailCodeService, "mailEnabled", true);
        }
    }
}
