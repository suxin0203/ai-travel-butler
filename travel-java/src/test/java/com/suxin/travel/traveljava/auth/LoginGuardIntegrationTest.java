package com.suxin.travel.traveljava.auth;

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

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录防爆破集成测试（契约 §2.2 v2.10）：IP+用户名 维度，10 分钟滚动窗口内
 * 连续 5 次失败后该组合 429（第 6 次起），登录成功即清零；其他用户名不受牵连。
 * MockMvc 的 remoteAddr 为 localhost，即 clientIp() 的本地路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'guarder%'");
        // 清掉全部爆破计数键（测试共享 db3，按前缀精确清理）
        Set<String> keys = redis.keys("login:fail:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private int tryLogin(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        return result.getResponse().getStatus();
    }

    @Test
    @DisplayName("连续 5 次错密码后第 6 次 429；其他用户名不受牵连")
    void lockAfterFiveFailures() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"guarder1\",\"password\":\"right123\"}"));

        for (int i = 1; i <= 5; i++) {
            assertEquals(400, tryLogin("guarder1", "wrong-" + i), "第 " + i + " 次失败应是 400");
        }
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guarder1\",\"password\":\"whatever\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("尝试次数过多，请 10 分钟后再试"));

        // 另一个用户名同 IP 也不受牵连（维度是 IP+用户名，不是纯 IP）
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"guarder2\",\"password\":\"right123\"}"));
        assertEquals(400, tryLogin("guarder2", "wrong"), "其他用户名不应被锁定");
    }

    @Test
    @DisplayName("登录成功清零计数：错 1 次 → 正确登录 → 再 5 次失败才锁定")
    void successResetsCounter() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"guarder3\",\"password\":\"right123\"}"));

        assertEquals(400, tryLogin("guarder3", "wrong"));
        assertEquals(200, tryLogin("guarder3", "right123"), "正确密码应正常登录");

        // 计数键应被清零（remoteAddr=127.0.0.1 本地路径，键即 login:fail:127.0.0.1:guarder3）
        String count = redis.opsForValue().get("login:fail:127.0.0.1:guarder3");
        assertEquals(null, count, "成功登录后计数键应被清零");

        for (int i = 1; i <= 5; i++) {
            assertEquals(400, tryLogin("guarder3", "wrong-again-" + i), "清零后重新累积，第 " + i + " 次失败仍是 400");
        }
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"guarder3\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
