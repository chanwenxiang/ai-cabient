package com.aicabinet.trade.auth;

import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.support.ServerBootMarker;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * refresh 过期宽限期（validateForRefresh）行为：
 * 过期在宽限内可换新 / 超宽限期拒绝 / 已吊销拒绝 / 业务接口仍拒绝过期 token。
 */
@ExtendWith(MockitoExtension.class)
class JwtServiceRefreshGraceTest {

    private static final String SECRET = "unit-test-jwt-secret-key-32bytes!!";
    private static final long BOOT = 1728800000000L;
    private static final long GRACE_SECONDS = 3600L;

    @Mock private ServerBootMarker serverBootMarker;
    @Mock private SessionRevocationService sessionRevocationService;

    private JwtService jwtService;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties(
                SECRET, 1800L, true, false, 5, 15, null, GRACE_SECONDS);
        jwtService = new JwtService(props, serverBootMarker, sessionRevocationService);
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        lenient().when(serverBootMarker.epochMillis()).thenReturn(BOOT);
        lenient().when(sessionRevocationService.isRevoked(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
    }

    /** 手工签发一个 boot 匹配、过期 offsetSeconds 秒的会话 token。 */
    private String expiredToken(long offsetSeconds, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(jti)
                .subject("13800138001")
                .claim("boot", BOOT)
                .claim("scope", "session")
                .issuedAt(Date.from(now.minusSeconds(offsetSeconds + 60)))
                .expiration(Date.from(now.minusSeconds(offsetSeconds)))
                .signWith(key)
                .compact();
    }

    @Test
    void validateForRefresh_expiredWithinGrace_succeeds() {
        String token = expiredToken(300, "jti-grace-ok"); // 过期 5 分钟 < 1h 宽限

        JwtService.SessionPrincipal principal = jwtService.validateForRefresh(token);

        assertEquals(13800138001L, principal.userId());
        assertEquals("jti-grace-ok", principal.jti());
    }

    @Test
    void validateForRefresh_expiredBeyondGrace_rejected() {
        String token = expiredToken(GRACE_SECONDS + 60, "jti-grace-out"); // 过期 1h+1min

        assertThrows(JwtService.InvalidSessionTokenException.class,
                () -> jwtService.validateForRefresh(token));
    }

    @Test
    void validateForRefresh_revokedJti_rejectedEvenWithinGrace() {
        String token = expiredToken(60, "jti-revoked");
        when(sessionRevocationService.isRevoked("jti-revoked")).thenReturn(true);

        assertThrows(JwtService.InvalidSessionTokenException.class,
                () -> jwtService.validateForRefresh(token));
    }

    @Test
    void validateForRefresh_bootMismatch_rejected() {
        // 过期但在宽限内，但 boot 是别的进程启动时刻（服务已重启）
        Instant now = Instant.now();
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject("13800138001")
                .claim("boot", BOOT - 1)
                .claim("scope", "session")
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();

        assertThrows(JwtService.InvalidSessionTokenException.class,
                () -> jwtService.validateForRefresh(token));
    }

    @Test
    void validateAndGetPrincipal_expiredToken_stillRejected() {
        // 业务接口路径不变：过期 token 一律拒绝（只能走 refresh 换新）
        String token = expiredToken(60, "jti-biz");

        assertThrows(ExpiredJwtException.class,
                () -> jwtService.validateAndGetPrincipal(token));
    }

    @Test
    void validateForRefresh_unexpiredToken_stillWorks() {
        String token = jwtService.createToken(13800138001L);

        JwtService.SessionPrincipal principal = jwtService.validateForRefresh(token);

        assertEquals(13800138001L, principal.userId());
        assertTrue(principal.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void revokeTokenQuietly_expiredWithinGrace_passesGraceToRevocation() {
        // e2e-4 实证缺口：过期 token 的 revoke 若不带 grace，黑名单写不进去 ⇒ 轮换/登出拦不住宽限窗口重放
        String token = expiredToken(60, "jti-grace-revoke");

        jwtService.revokeTokenQuietly(token);

        org.mockito.Mockito.verify(sessionRevocationService).revoke(
                org.mockito.ArgumentMatchers.eq("jti-grace-revoke"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(GRACE_SECONDS));
    }
}
