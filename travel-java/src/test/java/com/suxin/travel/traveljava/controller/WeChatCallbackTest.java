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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微信回调集成测试：签名验证、绑定码绑定全流程（注册→生成绑定码→发消息→openid 落库）。
 * 测试配置 wechat.token=test-wx-token，channels.wechat=false（渠道关闭不影响回调）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeChatCallbackTest {

    private static final String TOKEN = "test-wx-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM schedule_item");
        jdbcTemplate.update("DELETE FROM trip");
        jdbcTemplate.update("DELETE FROM user");
    }

    private String sign(String timestamp, String nonce) throws Exception {
        List<String> list = new ArrayList<>(List.of(TOKEN, timestamp, nonce));
        Collections.sort(list);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(String.join("", list).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    @DisplayName("GET 签名验证：正确签名返回 echostr，错误签名拒绝")
    void verifySignature() throws Exception {
        String ts = "1700000000";
        String nonce = "nonce123";
        String echo = "echotest";
        mockMvc.perform(get("/api/wechat/callback")
                        .param("signature", sign(ts, nonce))
                        .param("timestamp", ts).param("nonce", nonce).param("echostr", echo))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals(echo, result.getResponse().getContentAsString()));

        mockMvc.perform(get("/api/wechat/callback")
                        .param("signature", "bad")
                        .param("timestamp", ts).param("nonce", nonce).param("echostr", echo))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals("signature invalid",
                        result.getResponse().getContentAsString()));
    }

    @Test
    @DisplayName("绑定全流程：注册→生成绑定码→发'绑定 用户名 码'→openid 落库")
    void bindFlow() throws Exception {
        // 注册并登录（拿 token 生成绑定码）
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"wxbinder\",\"password\":\"pass123\"}"));
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"wxbinder\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = login.getResponse().getContentAsString();
        String token = body.substring(body.indexOf("\"token\":\"") + 9,
                body.indexOf("\"", body.indexOf("\"token\":\"") + 9));

        // 生成绑定码
        MvcResult codeResult = mockMvc.perform(post("/api/wechat/bindcode")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expireMinutes").value(10))
                .andReturn();
        String codeBody = codeResult.getResponse().getContentAsString();
        String code = codeBody.substring(codeBody.indexOf("\"code\":\"") + 8,
                codeBody.indexOf("\"", codeBody.indexOf("\"code\":\"") + 8));

        // 模拟微信消息：绑定 wxbinder {code}
        long uid = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username='wxbinder'", Long.class);
        String xml = "<xml><ToUserName><![CDATA[gh_test]]></ToUserName>"
                + "<FromUserName><![CDATA[oX-openid-abc]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[绑定 wxbinder " + code + "]]></Content></xml>";
        MvcResult callback = mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", sign("1700000000", "nonce123"))
                        .param("timestamp", "1700000000").param("nonce", "nonce123")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isOk())
                .andReturn();
        String reply = callback.getResponse().getContentAsString();
        assertTrue(reply.contains("绑定成功"), "应回复绑定成功，实际=" + reply);
        String openid = jdbcTemplate.queryForObject(
                "SELECT wechat_openid FROM user WHERE id=?", String.class, uid);
        assertEquals("oX-openid-abc", openid);

        // 错误绑定码：拒绝
        redis.delete("wx:bindcode:" + uid);
        String badXml = xml.replace("oX-openid-abc", "oX-hacker").replace(code, "000000");
        MvcResult badCallback = mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", sign("1700000000", "nonce123"))
                        .param("timestamp", "1700000000").param("nonce", "nonce123")
                        .contentType(MediaType.APPLICATION_XML).content(badXml))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(badCallback.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("用户名或验证码不对"),
                "错误文案应统一防用户名枚举");

        // 伪造请求（验签不过）→ 按 success 吞掉，openid 不得落库
        mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", "forged").param("timestamp", "1").param("nonce", "n")
                        .contentType(MediaType.APPLICATION_XML).content(xml))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString().length() >= 0));
        String openidAfterForge = jdbcTemplate.queryForObject(
                "SELECT wechat_openid FROM user WHERE username='wxbinder'", String.class);
        assertEquals("oX-openid-abc", openidAfterForge, "伪造绑定不得覆盖已有 openid");
    }

    @Test
    @DisplayName("v2.7 主格式：直接发送 6 位验证码即完成绑定（按在途码反查归属）")
    void bindByCodeOnly() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"codebinder\",\"password\":\"pass123\"}"));
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"codebinder\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = loginResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = loginBody.substring(loginBody.indexOf("\"token\"") + 9,
                loginBody.indexOf("\"", loginBody.indexOf("\"token\"") + 10));
        MvcResult codeResult = mockMvc.perform(post("/api/wechat/bindcode")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String codeBody = codeResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = codeBody.substring(codeBody.indexOf("\"code\":\"") + 8,
                codeBody.indexOf("\"", codeBody.indexOf("\"code\":\"") + 8));

        long uid = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username='codebinder'", Long.class);
        String xml = "<xml><ToUserName><![CDATA[gh_test]]></ToUserName>"
                + "<FromUserName><![CDATA[oX-code-only]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + code + "]]></Content></xml>";
        MvcResult callback = mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", sign("1700000000", "nonce123"))
                        .param("timestamp", "1700000000").param("nonce", "nonce123")
                        .contentType(MediaType.APPLICATION_XML).content(xml))
                .andExpect(status().isOk())
                .andReturn();
        String reply = callback.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(reply.contains("绑定成功"), "纯 6 位码应绑定成功，实际=" + reply);
        // 被动回复信封：ToUserName=用户 openid，FromUserName=测试号账号（此前硬编码应用名被微信静默丢弃）
        assertTrue(reply.contains("<ToUserName><![CDATA[oX-code-only]]></ToUserName>"), "回复收件人应为用户 openid");
        assertTrue(reply.contains("<FromUserName><![CDATA[gh_test]]></FromUserName>"), "回复发件人应为测试号账号");
        assertEquals("oX-code-only", jdbcTemplate.queryForObject(
                "SELECT wechat_openid FROM user WHERE id=?", String.class, uid));

        // 错误的 6 位码（无在途码匹配）→ 统一失败文案
        mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", sign("1700000000", "nonce123"))
                        .param("timestamp", "1700000000").param("nonce", "nonce123")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml.replace(code, "654321")))
                .andExpect(status().isOk());
        MvcResult bad = mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", sign("1700000000", "nonce123"))
                        .param("timestamp", "1700000000").param("nonce", "nonce123")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml.replace(code, "654321")))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(bad.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("用户名或验证码不对"),
                "纯码错误也应走统一失败文案");
    }

    @Test
    @DisplayName("换绑抢占：openid 已绑在旧账号时，新账号凭码绑定自动解除旧持有")
    void bindSwitchesAccounts() throws Exception {
        // 旧账号已持有该 openid
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"oldholder\",\"password\":\"pass123\"}"));
        jdbcTemplate.update("UPDATE user SET wechat_openid='oX-switch' WHERE username='oldholder'");

        // 新账号生成码并以同一 openid 发纯数字码
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"switcher\",\"password\":\"pass123\"}"));
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"switcher\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = loginResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = loginBody.substring(loginBody.indexOf("\"token\"") + 9,
                loginBody.indexOf("\"", loginBody.indexOf("\"token\"") + 10));
        MvcResult codeResult = mockMvc.perform(post("/api/wechat/bindcode")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String codeBody = codeResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = codeBody.substring(codeBody.indexOf("\"code\":\"") + 8,
                codeBody.indexOf("\"", codeBody.indexOf("\"code\":\"") + 8));

        String xml = "<xml><ToUserName><![CDATA[gh_test]]></ToUserName>"
                + "<FromUserName><![CDATA[oX-switch]]></FromUserName>"
                + "<CreateTime>1700000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + code + "]]></Content></xml>";
        MvcResult callback = mockMvc.perform(post("/api/wechat/callback")
                        .param("signature", sign("1700000000", "nonce123"))
                        .param("timestamp", "1700000000").param("nonce", "nonce123")
                        .contentType(MediaType.APPLICATION_XML).content(xml))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(callback.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("绑定成功"),
                "换绑应成功");

        // 新账号持有 openid，旧账号被解除
        assertEquals("oX-switch", jdbcTemplate.queryForObject(
                "SELECT wechat_openid FROM user WHERE username='switcher'", String.class));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT wechat_openid FROM user WHERE username='oldholder'", String.class));
    }
}
