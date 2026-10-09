package com.aicabinet.jiangyi.auth;

import com.aicabinet.jiangyi.client.TradeInternalClient.JiangyiDeviceView;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 将邑设备 token 单测（CB-022，方案 §12：token 负向 + round-trip）。
 * <p>负向用例刻意覆盖三条 fail-closed 边界：密钥未配置、强度不足、伪造签名。</p>
 */
class DeviceTokenServiceTest {

    private static final String SECRET_32 = "jiangyi-test-secret-0123456789abcdef!"; // 33 bytes
    private static JiangyiDeviceView device() {
        return new JiangyiDeviceView("D1", "sn-abc", "CQYB11253", "model76", null,
                "BOUND", 3L, Instant.now(), null);
    }

    @Test
    void issueAndVerifyRoundTrip() {
        DeviceTokenService service = new DeviceTokenService(SECRET_32, 3600);
        String token = service.issue(device());
        Claims claims = service.verify(token);
        assertEquals("D1", claims.getSubject());
        assertEquals("sn-abc", claims.get(DeviceTokenService.CLAIM_SN));
        assertEquals("CQYB11253", claims.get(DeviceTokenService.CLAIM_IDENTIFIER));
        assertEquals(3L, claims.get(DeviceTokenService.CLAIM_VER, Long.class));
        assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());
    }

    @Test
    void issueFailsClosedWhenSecretMissing() {
        DeviceTokenService service = new DeviceTokenService("", 3600);
        assertThrows(IllegalStateException.class, () -> service.issue(device()));
        assertThrows(IllegalStateException.class, service::ensureKeyAvailable);
    }

    @Test
    void issueFailsClosedWhenSecretTooShort() {
        // HS256 需 ≥32 字节；28 字节必须被拒，否则弱密钥静默放行
        DeviceTokenService service = new DeviceTokenService("short-secret-28-bytes-x!!!!!", 3600);
        assertThrows(IllegalStateException.class, () -> service.issue(device()));
    }

    @Test
    void verifyRejectsTamperedSignature() {
        DeviceTokenService service = new DeviceTokenService(SECRET_32, 3600);
        String token = service.issue(device());
        String tampered = token.substring(0, token.length() - 4) + "beef";
        assertThrows(JwtException.class, () -> service.verify(tampered));
    }

    @Test
    void verifyRejectsTokenFromDifferentSecret() {
        DeviceTokenService a = new DeviceTokenService(SECRET_32, 3600);
        DeviceTokenService b = new DeviceTokenService("another-secret-0123456789abcdef!!", 3600);
        String token = a.issue(device());
        assertThrows(JwtException.class, () -> b.verify(token));
    }

    @Test
    void expiredTokenRejected() throws InterruptedException {
        DeviceTokenService service = new DeviceTokenService(SECRET_32, 1);
        String token = service.issue(device());
        Thread.sleep(1500);
        assertThrows(JwtException.class, () -> service.verify(token));
    }

    @Test
    void nullTokenVersionDefaultsZero() {
        DeviceTokenService service = new DeviceTokenService(SECRET_32, 3600);
        JiangyiDeviceView noVer = new JiangyiDeviceView("D1", "sn-abc", "CQYB11253", null, null,
                "BOUND", null, null, null);
        String token = service.issue(noVer);
        Claims claims = assertDoesNotThrow(() -> service.verify(token));
        assertEquals(0L, claims.get(DeviceTokenService.CLAIM_VER, Long.class));
        assertNotEquals(0, token.length());
    }
}
