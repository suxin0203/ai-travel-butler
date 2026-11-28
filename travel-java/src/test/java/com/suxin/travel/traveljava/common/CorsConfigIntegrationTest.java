package com.suxin.travel.traveljava.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 白名单集成测试。
 * 背景：内网手机经 vite 代理访问时，POST 仍带 Origin（=本机内网 IP），
 * Host 被代理改写成 localhost:8080，后端按跨域校验——白名单不含内网段就
 * 403 "Invalid CORS request"（页面能开、登录全挂的根因）。
 * 覆盖：内网网段模式放行（预检+实际请求）、精确 localhost 放行、陌生来源拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("内网网段 Origin 的预检请求放行且回显具体来源")
    void preflightFromLanOriginAllowed() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "http://192.168.101.121:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                // allowCredentials=true 时回显具体来源而非 *
                .andExpect(header().string("Access-Control-Allow-Origin",
                        "http://192.168.101.121:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("内网网段 Origin 的实际请求放行（401 业务响应而非 403 CORS 拒绝）")
    void actualRequestFromLanOriginAllowed() throws Exception {
        // 未登录 → 401 是业务响应；若白名单没放行，这里会是 403 Invalid CORS request
        mockMvc.perform(get("/api/auth/me")
                        .header("Origin", "http://192.168.101.121:5173"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("精确 localhost Origin 依旧放行（白名单精确值兼容模式匹配）")
    void exactLocalhostOriginStillAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("白名单外来源仍被拒绝（安全收敛不回退）")
    void unknownOriginRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Origin", "http://evil.example.com"))
                .andExpect(status().isForbidden())
                // 断言响应体钉死走的是 DefaultCorsProcessor 拒绝路径，
                // 防止将来新增其他 403 生产者后本用例静默失去鉴别力
                .andExpect(content().string("Invalid CORS request"));
    }
}
