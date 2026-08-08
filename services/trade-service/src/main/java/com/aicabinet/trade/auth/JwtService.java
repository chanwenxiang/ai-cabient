package com.aicabinet.trade.auth;

import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.support.ServerBootMarker;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtService {

    static final String BOOT_CLAIM = "boot";

    private final SecretKey key;
    private final long expirationSeconds;
    private final ServerBootMarker serverBootMarker;

    public JwtService(AuthProperties authProperties, ServerBootMarker serverBootMarker) {
        String secret = authProperties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("aicabinet.auth.jwt-secret must be configured");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = authProperties.expirationSeconds();
        this.serverBootMarker = serverBootMarker;
    }

    public String createToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(BOOT_CLAIM, serverBootMarker.epochMillis())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
    }

    /** 校验签名、过期与服务启动 epoch，返回 userId */
    public Long validateAndGetUserId(String token) {
        Claims claims = parseClaims(token);
        Object bootObj = claims.get(BOOT_CLAIM);
        if (bootObj == null) {
            throw new InvalidSessionTokenException("missing boot claim");
        }
        long boot = bootObj instanceof Number n ? n.longValue() : Long.parseLong(bootObj.toString());
        if (boot != serverBootMarker.epochMillis()) {
            throw new InvalidSessionTokenException("server restarted");
        }
        return Long.parseLong(claims.getSubject());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public static class InvalidSessionTokenException extends RuntimeException {
        public InvalidSessionTokenException(String message) {
            super(message);
        }
    }
}
