package com.suxin.travel.traveljava.service;

import com.suxin.travel.traveljava.entity.ScheduleItem;
import com.suxin.travel.traveljava.notify.NotifyResult;
import com.suxin.travel.traveljava.notify.NotifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 推送渠道偏好集成测试（契约 §2.5b/§4.6）。
 * 覆盖：新用户默认双开、PUT 回写与 me 回读、缺字段 400、
 * 偏好关闭的渠道被编排器视为不可用（全部不可用 → NO_CHANNEL，开启后 → MAIL SUCCESS）。
 * 渠道环境：test profile 下 channels.sse=false、channels.wechat=false，邮件发送被 @MockBean 吞掉——
 * 因此「MAIL 可用」完全由偏好 + 绑定邮箱驱动，可精确断言过滤行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotifyChannelsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NotifyService notifyService;
    @org.springframework.boot.test.mock.mockito.MockBean
    private JavaMailSender mailSender;

    private static final long ITEM_ID = 900001L;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM reminder_log");
        jdbcTemplate.update("DELETE FROM schedule_item");
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'notifyer%'");
    }

    private String registerAndLogin(String username, String email) throws Exception {
        String emailPart = email == null ? "" : ",\"email\":\"" + email + "\"";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"" + emailPart + "}"));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.substring(body.indexOf("\"token\":\"") + 9,
                body.indexOf("\"", body.indexOf("\"token\":\"") + 9));
    }

    @Test
    @DisplayName("新用户默认双开；PUT 关闭后回显与 me 回读一致；缺字段 400")
    void defaultOnThenToggle() throws Exception {
        String token = registerAndLogin("notifyer1", null);

        // 新用户默认双开（列 DEFAULT 1）
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notify.mail").value(true))
                .andExpect(jsonPath("$.data.notify.wechat").value(true));

        // 关闭邮件偏好
        mockMvc.perform(put("/api/auth/notify-channels").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mail\":false,\"wechat\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mail").value(false))
                .andExpect(jsonPath("$.data.wechat").value(true));
        String stored = jdbcTemplate.queryForObject(
                "SELECT notify_mail FROM user WHERE username='notifyer1'", String.class);
        assertEquals("0", stored);

        // me 回读一致
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notify.mail").value(false))
                .andExpect(jsonPath("$.data.notify.wechat").value(true));

        // 缺字段 → 400
        mockMvc.perform(put("/api/auth/notify-channels").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mail\":true}"))
                .andExpect(status().isBadRequest());

        // 非布尔值 → 400（HttpMessageNotReadable 路径）
        mockMvc.perform(put("/api/auth/notify-channels").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mail\":\"abc\",\"wechat\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("偏好过滤编排：邮件关 → NO_CHANNEL；邮件开（已绑定）→ MAIL SUCCESS")
    void preferenceFiltersChannels() throws Exception {
        String token = registerAndLogin("notifyer2", "pref@test.com");
        long uid = Long.parseLong(jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username='notifyer2'", String.class).toString());

        // 关闭邮件偏好 → SSE/微信在测试环境本就不可用 → 编排侧全渠道静默跳过（attempted=0；
        // NO_CHANNEL 收口日志由 ReminderConsumer 在 attempted=0 时写，此处直调编排层不覆盖该路径）
        mockMvc.perform(put("/api/auth/notify-channels").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mail\":false,\"wechat\":false}"))
                .andExpect(status().isOk());
        NotifyResultCheck check0 = sendAllAndCheck(uid);
        assertEquals(0, check0.attempted);
        assertFalse(check0.hasMailSuccess, "偏好关闭时不应有 MAIL 发送记录");

        // 开启邮件偏好（已绑定 email）→ 邮件渠道被编排（发送由 mock 吞掉）→ MAIL SUCCESS
        mockMvc.perform(put("/api/auth/notify-channels").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mail\":true,\"wechat\":false}"))
                .andExpect(status().isOk());
        NotifyResultCheck check1 = sendAllAndCheck(uid);
        assertTrue(check1.attempted >= 1, "邮件偏好开启后应尝试邮件渠道");
        assertTrue(check1.hasMailSuccess, "应存在 MAIL SUCCESS 日志");
    }

    /** 调 NotifyService.sendAll 并汇总 reminder_log 断言材料 */
    private NotifyResultCheck sendAllAndCheck(long uid) {
        jdbcTemplate.update("DELETE FROM reminder_log WHERE schedule_item_id=?", ITEM_ID);
        ScheduleItem item = new ScheduleItem();
        item.setId(ITEM_ID);
        item.setUserId(uid);
        item.setTitle("偏好过滤");
        item.setStartTime(LocalDateTime.of(2026, 11, 27, 9, 30));
        item.setRemindMinutesBefore(30);
        NotifyResult result = notifyService.sendAll(item);
        Long noChannel = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reminder_log WHERE schedule_item_id=? AND channel='NONE' AND detail='NO_CHANNEL'",
                Long.class, ITEM_ID);
        Long mailSuccess = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reminder_log WHERE schedule_item_id=? AND channel='MAIL' AND status='SUCCESS'",
                Long.class, ITEM_ID);
        return new NotifyResultCheck(result.attempted(), noChannel != null && noChannel > 0,
                mailSuccess != null && mailSuccess > 0);
    }

    /** 断言材料简单封装 */
    private record NotifyResultCheck(int attempted, boolean hasNoChannel, boolean hasMailSuccess) {
    }
}
