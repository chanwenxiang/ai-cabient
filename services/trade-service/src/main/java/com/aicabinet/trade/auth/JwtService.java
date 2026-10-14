package com.aicabinet.trade.auth;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.support.ServerBootMarker;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
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
    /** 过期 token 仅允许 refresh 的宽限期（秒）。 */
    private final long refreshGraceSeconds;
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
        this.refreshGraceSeconds = authProperties.refreshGraceSeconds();
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
        return toSessionPrincipal(claims);
    }

    /**
     * refresh 专用校验：允许「过期但在宽限期内」的 token 换新。
     *
     * <p>成因：会话 JWT 有效期默认 30 分钟，此前 refresh 走标准验签（parseSignedClaims 对过期
     * token 直接抛 ExpiredJwtException），导致 refresh 只能「轮换未过期 token」而不能「续命
     * 已过期会话」——前端闲置 30 分钟后回来必然被踢回登录页。现允许 exp > now - 宽限期 的
     * token 走 refresh，安全性由三点收敛：① 仅 refresh 端点可用（业务接口仍走
     * {@link #validateAndGetPrincipal}，一律拒绝过期 token）；② jti 吊销黑名单照常校验
     * （登出/踢人立即失效）；③ boot claim 校验照常（服务重启全员失效）。
     * 宽限期配置：aicabinet.auth.refresh-grace-seconds，默认 86400（24h）。</p>
     */
    public SessionPrincipal validateForRefresh(String token) {
        Claims claims;
        try {
            claims = parseClaims(token);
        } catch (ExpiredJwtException e) {
            claims = e.getClaims();
            Instant exp = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            if (exp == null || exp.isBefore(Instant.now().minusSeconds(refreshGraceSeconds))) {
                throw new InvalidSessionTokenException("token expired beyond refresh grace");
            }
        }
        return toSessionPrincipal(claims);
    }

    /** 校验签名、过期与服务启动 epoch，返回 userId；拒绝 2FA challenge 等非会话 scope。 */
    public Long validateAndGetUserId(String token) {
        return validateAndGetPrincipal(token).userId();
    }

    /** scope / boot / 吊销名单 / 账号类型归一的公共校验，供正常与 refresh 宽限两条路径复用。 */
    private SessionPrincipal toSessionPrincipal(Claims claims) {
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

    /** 吊销会话 token（登出 / refresh 轮换）；无效签名忽略。grace 感知：过期但在宽限内的 token 也入黑名单，防宽限窗口重放。 */
    public void revokeTokenQuietly(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            Claims claims;
            try {
                claims = parseClaims(token);
            } catch (ExpiredJwtException e) {
                // 过期但可能在宽限期内：仍需吊销，否则轮换/登出拦不住宽限窗口内的 refresh 重放
                claims = e.getClaims();
            }
            assertBoot(claims);
            String jti = claims.getId();
            Instant exp = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            sessionRevocationService.revoke(jti, exp, refreshGraceSeconds);
        } catch (Exception ignored) {
            // 登出幂等：坏 token / boot 不匹配不抛错
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
