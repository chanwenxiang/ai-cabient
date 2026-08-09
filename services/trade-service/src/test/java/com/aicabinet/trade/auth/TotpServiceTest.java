package com.aicabinet.trade.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {

    private final TotpService service = new TotpService();

    /** RFC 6238 附录 B 官方测试向量（secret = "12345678901234567890"）。 */
    @Test
    void generateCode_shouldMatchRfc6238Vectors() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertEquals("287082", service.generateCode(secret, 59L / 30L));
        assertEquals("081804", service.generateCode(secret, 1111111109L / 30L));
        assertEquals("050471", service.generateCode(secret, 1111111111L / 30L));
        assertEquals("005924", service.generateCode(secret, 1234567890L / 30L));
        assertEquals("279037", service.generateCode(secret, 2000000000L / 30L));
        assertEquals("353130", service.generateCode(secret, 20000000000L / 30L));
    }

    @Test
    void base32RoundTrip_shouldPreserveBytes() {
        byte[] bytes = "secret-key-0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String encoded = TotpService.base32Encode(bytes);
        assertEquals(bytes.length, TotpService.base32Decode(encoded).length);
    }

    @Test
    void verify_shouldRejectWrongOrMalformedCode() {
        String secret = service.generateSecret();
        assertFalse(service.verify(secret, "123456"));
        assertFalse(service.verify(secret, "abcdef"));
        assertFalse(service.verify(secret, "12345"));
        assertFalse(service.verify(null, "123456"));
        assertFalse(service.verify(secret, null));
    }

    @Test
    void otpauthUri_shouldContainSecretAndIssuer() {
        String secret = service.generateSecret();
        String uri = service.otpauthUri(secret, "13900000001");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + secret));
        assertTrue(uri.contains("issuer="));
    }
}
