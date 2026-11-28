package com.suxin.travel.traveljava.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** JwtUtil 纯单元测试：签发、解析、篡改拒绝、错误密钥启动失败 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("unit-test-secret-0123456789abcdef0123456789abcdef", 1);
    }

    @Test
    @DisplayName("签发的 token 能解析回相同身份与 jti")
    void createAndParse() {
        JwtUtil.TokenInfo info = jwtUtil.create(42L, "suxin");
        JwtUtil.ParsedToken parsed = jwtUtil.parse(info.token());
        assertNotNull(parsed);
        assertEquals(42L, parsed.uid());
        assertEquals("suxin", parsed.username());
        assertEquals(info.jti(), parsed.jti());
    }

    @Test
    @DisplayName("篡改内容的 token 解析返回 null（验签失败不抛异常）")
    void tamperedTokenRejected() {
        JwtUtil.TokenInfo info = jwtUtil.create(1L, "a");
        String tampered = info.token().substring(0, info.token().length() - 3) + "abc";
        assertNull(jwtUtil.parse(tampered));
    }

    @Test
    @DisplayName("垃圾字符串解析返回 null")
    void garbageRejected() {
        assertNull(jwtUtil.parse("not-a-jwt"));
        assertNull(jwtUtil.parse(""));
    }

    @Test
    @DisplayName("密钥长度不足 32 字节时启动即失败")
    void shortSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("short", 1));
    }
}
