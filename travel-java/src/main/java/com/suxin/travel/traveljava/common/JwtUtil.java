package com.suxin.travel.traveljava.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（jjwt 0.12 API）。
 * 设计要点：token 本身无状态，但每个 token 带唯一 jti，并在 Redis 保存
 * login:token:{uid}:{jti} 白名单（TTL = token 剩余有效期）。
 * 这样既保留 JWT 免查库的优点，又能"删 key 即强制下线"——
 * 这是 JWT 无状态与业务可控登录态矛盾的折中方案。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireHours;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-hours:168}") long expireHours) {
        // HS256 要求密钥至少 256 位（32 字节），启动期就失败，避免运行期才暴露
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret 未配置或长度不足 32 字节，请检查 application-local.yml");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireHours = expireHours;
    }

    public record TokenInfo(String token, String jti, long expiresAtEpochSecond) {
    }

    public record ParsedToken(long uid, String username, String jti) {
    }

    public TokenInfo create(long uid, String username) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expireHours * 3600);
        String token = Jwts.builder()
                .subject(String.valueOf(uid))
                .claim("username", username)
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new TokenInfo(token, jti, exp.getEpochSecond());
    }

    /**
     * 解析并验签。签名不合法 / 已过期 / 格式错误统一返回 null，
     * 由调用方决定返回 401，不在这里暴露具体原因。
     */
    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new ParsedToken(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.getId()
            );
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long expireHours() {
        return expireHours;
    }
}
