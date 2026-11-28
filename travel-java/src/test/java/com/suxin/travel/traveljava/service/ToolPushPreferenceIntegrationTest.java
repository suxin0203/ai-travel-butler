package com.suxin.travel.traveljava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suxin.travel.traveljava.llm.ToolRegistry;
import com.suxin.travel.traveljava.notify.NotifyResult;
import com.suxin.travel.traveljava.notify.NotifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FC 推送偏好工具集成测试（契约 §3.1 v2.9 新增的 set_push_preference / get_push_preference）。
 * 覆盖：单渠道开关合并写入（另一渠道不变）、get 返回偏好与绑定状态、非法参数 ok:false、
 * 工具写入的偏好对发送编排真实生效（与设置页 PUT 同一条 service 规则的端到端证明）。
 * 渠道环境与 NotifyChannelsIntegrationTest 一致：test profile 下仅邮件渠道可被编排。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ToolPushPreferenceIntegrationTest {

    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NotifyService notifyService;
    @MockBean
    private JavaMailSender mailSender;

    private static final long ITEM_ID = 900101L;
    private long uid;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM reminder_log");
        jdbcTemplate.update("DELETE FROM schedule_item");
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE 'tooler%'");
    }

    /** 注册并登录，取 uid；email 可空（未绑定邮箱） */
    private long register(String username, String email) throws Exception {
        String emailPart = email == null ? "" : ",\"email\":\"" + email + "\"";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"pass123\"" + emailPart + "}"))
                .andExpect(status().isOk());
        uid = Long.parseLong(jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username='" + username + "'", String.class).toString());
        return uid;
    }

    /** 直调工具并解析返回的 JSON（工具层入口与 ChatService 循环内一致：execute(uid, name, args)） */
    private JsonNode callTool(String name, String argsJson) throws Exception {
        String raw = toolRegistry.execute(uid, name, argsJson);
        return objectMapper.readTree(raw);
    }

    @Test
    @DisplayName("set 单渠道合并写入：关邮件不动微信；再开微信邮件仍保持关闭")
    void setMergesWithCurrentPreference() throws Exception {
        register("tooler1", null);
        // 新用户默认双开（§2.5b），关邮件 → 只邮件变 false
        JsonNode r1 = callTool("set_push_preference", "{\"channel\":\"mail\",\"enabled\":false}");
        assertTrue(r1.get("ok").asBoolean());
        assertFalse(r1.get("mail").asBoolean(), "邮件应被关闭");
        assertTrue(r1.get("wechat").asBoolean(), "微信应保持开启（合并写入）");
        assertEquals("0", jdbcTemplate.queryForObject(
                "SELECT notify_mail FROM user WHERE id=?", String.class, uid));
        assertEquals("1", jdbcTemplate.queryForObject(
                "SELECT notify_wechat FROM user WHERE id=?", String.class, uid));

        // 再开微信 → 邮件保持 false（证明不是 PUT 全量覆盖语义）
        JsonNode r2 = callTool("set_push_preference", "{\"channel\":\"wechat\",\"enabled\":true}");
        assertTrue(r2.get("wechat").asBoolean());
        assertFalse(r2.get("mail").asBoolean(), "邮件应保持关闭");
    }

    @Test
    @DisplayName("get 返回偏好与绑定状态：绑邮箱 mailBound=true；不绑 wechatBound=false")
    void getReturnsPreferenceAndBinding() throws Exception {
        register("tooler2", "tooler2@test.com");
        JsonNode bound = callTool("get_push_preference", null);
        assertTrue(bound.get("ok").asBoolean());
        assertTrue(bound.get("mail").asBoolean(), "新用户默认双开");
        assertTrue(bound.get("wechat").asBoolean());
        assertTrue(bound.get("mailBound").asBoolean(), "已绑邮箱 → mailBound=true");
        assertFalse(bound.get("wechatBound").asBoolean(), "未绑微信 → wechatBound=false");

        register("tooler3", null);
        JsonNode unbound = callTool("get_push_preference", null);
        assertFalse(unbound.get("mailBound").asBoolean(), "未绑邮箱 → mailBound=false");
    }

    @Test
    @DisplayName("非法参数 ok:false：channel 越界 / enabled 非布尔 / 未知工具")
    void invalidArgsReturnOkFalse() throws Exception {
        register("tooler4", null);
        assertFalse(callTool("set_push_preference", "{\"channel\":\"sms\",\"enabled\":true}").get("ok").asBoolean());
        assertFalse(callTool("set_push_preference", "{\"channel\":\"mail\",\"enabled\":\"yes\"}").get("ok").asBoolean());
        assertFalse(callTool("set_push_preference", "{\"channel\":\"mail\"}").get("ok").asBoolean());
        assertFalse(callTool("no_such_tool", null).get("ok").asBoolean());
        // 非法调用不得改动库里的偏好
        assertEquals("1", jdbcTemplate.queryForObject(
                "SELECT notify_mail FROM user WHERE id=?", String.class, uid));
    }

    @Test
    @DisplayName("开启未绑定渠道：结果带绑定状态与引导提示（评审 P1-1，防静默失败无告知）")
    void enablingUnboundChannelCarriesHint() throws Exception {
        register("tooler6", null); // 未绑邮箱、未绑微信
        JsonNode r = callTool("set_push_preference", "{\"channel\":\"mail\",\"enabled\":true}");
        assertTrue(r.get("ok").asBoolean());
        assertTrue(r.get("mail").asBoolean());
        assertFalse(r.get("mailBound").asBoolean(), "未绑邮箱 → mailBound=false 随结果回传");
        assertTrue(r.get("message").asText().contains("绑定"), "message 应提示先去设置页绑定");
    }

    @Test
    @DisplayName("端到端：工具关邮件后编排侧 attempted=0；工具开邮件（已绑定）→ MAIL SUCCESS")
    void toolWrittenPreferenceDrivesOrchestration() throws Exception {
        register("tooler5", "tooler5@test.com");
        // 工具关邮件 → 与设置页关闭等价 → 全渠道静默跳过
        assertTrue(callTool("set_push_preference", "{\"channel\":\"mail\",\"enabled\":false}").get("ok").asBoolean());
        assertEquals(0, sendAllAndAttempted());

        // 工具开邮件 → 邮件渠道被编排（发送由 mock 吞掉）
        assertTrue(callTool("set_push_preference", "{\"channel\":\"mail\",\"enabled\":true}").get("ok").asBoolean());
        assertTrue(sendAllAndAttempted() >= 1, "工具开启邮件偏好后应尝试邮件渠道");
    }

    private int sendAllAndAttempted() {
        jdbcTemplate.update("DELETE FROM reminder_log WHERE schedule_item_id=?", ITEM_ID);
        com.suxin.travel.traveljava.entity.ScheduleItem item = new com.suxin.travel.traveljava.entity.ScheduleItem();
        item.setId(ITEM_ID);
        item.setUserId(uid);
        item.setTitle("工具偏好联动");
        item.setStartTime(LocalDateTime.of(2026, 11, 27, 9, 30));
        item.setRemindMinutesBefore(30);
        NotifyResult result = notifyService.sendAll(item);
        return result.attempted();
    }
}
