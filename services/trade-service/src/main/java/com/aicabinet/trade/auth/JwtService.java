package com.aicabinet.trade.auth;

import com.aicabinet.common.constants.CabinetConstants;
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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Component
@Getter
@Setter
public class JwtService {

    static final String BOOT_CLAIM = "boot";
    static final String SCOPE_CLAIM = "scope";
    /** account type: CONSUMER / OPERATOR */
    static final String ACCOUNT_TYPE_CLAIM = "act";
    static final String SCOPE_SESSION = "session";
    static final String SCOPE_TWO_FACTOR_CHALLENGE = "2FA_CHALLENGE";
    private static final long TWO_FACTOR_CHALLENGE_SECONDS = 300;

    private final SecretKey key;
    private final long expirationSeconds;
    private final ServerBootMarker serverBootMarker;
    private final SessionRevocationService sessionRevocationService;

    public JwtService(AuthProperties authProperties,
                      ServerBootMarker serverBootMarker,
                      SessionRevocationService sessionRevocationService) {
        String secret = authProperties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("aicabinet.auth.jwt-secret must be configured");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = authProperties.expirationSeconds();
        this.serverBootMarker = serverBootMarker;
        this.sessionRevocationService = sessionRevocationService;
    }

    public String createToken(Long userId) {
        return createToken(userId, null);
    }

    public String createToken(Long userId, String accountType) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(String.valueOf(userId))
                .claim(BOOT_CLAIM, serverBootMarker.epochMillis())
                .claim(SCOPE_CLAIM, SCOPE_SESSION)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key);
        String act = normalizeAccountType(accountType, userId);
        if (act != null) {
            builder.claim(ACCOUNT_TYPE_CLAIM, act);
        }
        return builder.compact();
    }

    /** 2FA 校验用短时 challenge token：仅允许调用 2FA 完成登录，不可访问业务接口。 */
    public String createTwoFactorChallengeToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
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
        assertBoot(claims);
        assertNotRevoked(claims);
        return Long.parseLong(claims.getSubject());
    }

    /** 校验签名、过期、boot 与吊销名单，返回会话主体。 */
    public SessionPrincipal validateAndGetPrincipal(String token) {
        Claims claims = parseClaims(token);
        String scope = claims.get(SCOPE_CLAIM, String.class);
        if (SCOPE_TWO_FACTOR_CHALLENGE.equals(scope)
                || (scope != null && !SCOPE_SESSION.equals(scope))) {
            throw new InvalidSessionTokenException("token scope not allowed for session APIs");
        }
        assertBoot(claims);
        assertNotRevoked(claims);
        long userId = Long.parseLong(claims.getSubject());
        String act = claims.get(ACCOUNT_TYPE_CLAIM, String.class);
        if (act == null || act.isBlank()) {
            act = userId >= CabinetConstants.OPERATOR_USER_ID_START
                    ? CabinetConstants.ACCOUNT_TYPE_OPERATOR
                    : CabinetConstants.ACCOUNT_TYPE_CONSUMER;
        }
        Instant exp = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
        return new SessionPrincipal(userId, act.trim().toUpperCase(), claims.getId(), exp);
    }

    /** 校验签名、过期与服务启动 epoch，返回 userId；拒绝 2FA challenge 等非会话 scope。 */
    public Long validateAndGetUserId(String token) {
        return validateAndGetPrincipal(token).userId();
    }

    /** 吊销仍有效的会话 token（登出 / refresh 轮换）；无效签名忽略。 */
    public void revokeTokenQuietly(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims = parseClaims(token);
            assertBoot(claims);
            String jti = claims.getId();
            Instant exp = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            sessionRevocationService.revoke(jti, exp);
        } catch (Exception ignored) {
            // 登出幂等：坏 token / 已过期不抛错
        }
    }

    private void assertNotRevoked(Claims claims) {
        String jti = claims.getId();
        if (jti != null && !jti.isBlank() && sessionRevocationService.isRevoked(jti)) {
            throw new InvalidSessionTokenException("token revoked");
        }
    }

    private void assertBoot(Claims claims) {
        Object bootObj = claims.get(BOOT_CLAIM);
        if (bootObj == null) {
            throw new InvalidSessionTokenException("missing boot claim");
        }
        long boot = bootObj instanceof Number n ? n.longValue() : Long.parseLong(bootObj.toString());
        if (boot != serverBootMarker.epochMillis()) {
            throw new InvalidSessionTokenException("server restarted");
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static String normalizeAccountType(String accountType, Long userId) {
        if (accountType != null && !accountType.isBlank()) {
            return accountType.trim().toUpperCase();
        }
        if (userId != null && userId >= CabinetConstants.OPERATOR_USER_ID_START) {
            return CabinetConstants.ACCOUNT_TYPE_OPERATOR;
        }
        if (userId != null) {
            return CabinetConstants.ACCOUNT_TYPE_CONSUMER;
        }
        return null;
    }

    public record SessionPrincipal(long userId, String accountType, String jti, Instant expiresAt) {}

    public static class InvalidSessionTokenException extends RuntimeException {
        public InvalidSessionTokenException(String message) {
            super(message);
        }
    }
}
