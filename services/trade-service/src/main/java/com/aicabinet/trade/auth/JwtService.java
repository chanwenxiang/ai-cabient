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
import lombok.Getter;
import lombok.Setter;

@Component
@Getter
@Setter
public class JwtService {

    static final String BOOT_CLAIM = "boot";
    static final String SCOPE_CLAIM = "scope";
    static final String SCOPE_TWO_FACTOR_CHALLENGE = "2FA_CHALLENGE";
    private static final long TWO_FACTOR_CHALLENGE_SECONDS = 300;

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
                .claim(SCOPE_CLAIM, "session")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
    }

    /** 2FA 校验用短时 challenge token：仅允许调用 2FA 完成登录，不可访问业务接口。 */
    public String createTwoFactorChallengeToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(BOOT_CLAIM, serverBootMarker.epochMillis())
                .claim(SCOPE_CLAIM, SCOPE_TWO_FACTOR_CHALLENGE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(TWO_FACTOR_CHALLENGE_SECONDS)))
                .signWith(key)
                .compact();
    }

    /** 校验 2FA challenge token，返回 userId；非 challenge 或已过期抛异常。 */
    public Long validateChallengeToken(String token) {
        Claims claims = parseClaims(token);
        if (!SCOPE_TWO_FACTOR_CHALLENGE.equals(claims.get(SCOPE_CLAIM, String.class))) {
            throw new InvalidSessionTokenException("not a 2FA challenge token");
        }
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


    public static class InvalidSessionTokenException extends RuntimeException {
        public InvalidSessionTokenException(String message) {
            super(message);
        }
    }
}
