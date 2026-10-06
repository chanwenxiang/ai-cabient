package com.aicabinet.trade.payout;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayoutFieldCipher} 单元测试。
 *
 * <p>🔴 本测试类钉住三条不可回退的行为：
 * <ol>
 *   <li>加密 round-trip（密文能解回明文）；</li>
 *   <li><b>fail-closed</b>：密钥缺失时<b>抛错</b>，绝不返回明文或静默降级；</li>
 *   <li>密文被篡改时 GCM 认证失败 ⇒ 抛错，<b>不返回篡改后的内容</b>。</li>
 * </ol>
 */
class PayoutFieldCipherTest {

    private static PayoutFieldCipher cipherWith(String base64Key) {
        return new PayoutFieldCipher(new PayoutEncryptionProperties(base64Key));
    }

    private static String validKey() {
        byte[] raw = new byte[PayoutEncryptionProperties.AES_256_KEY_BYTES];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7 + 13);
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void encryptThenDecrypt_returnsOriginalPlaintext() {
        PayoutFieldCipher cipher = cipherWith(validKey());
        String plain = "6222021234567890123";

        String encrypted = cipher.encrypt(plain);

        assertNotNull(encrypted);
        assertNotEquals(plain, encrypted, "密文不得等于明文");
        assertTrue(encrypted.contains(":"), "密文格式应为 base64(iv):base64(ct)");
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void encrypt_twoCalls_produceDifferentCiphertexts() {
        PayoutFieldCipher cipher = cipherWith(validKey());
        String plain = "6222021234567890123";

        // 🔴 每次随机 IV ⇒ 同一账号两次加密结果必须不同（否则相同密文可被关联识别）
        assertNotEquals(cipher.encrypt(plain), cipher.encrypt(plain));
    }

    @Test
    void decrypt_withDifferentKey_throws_failClosed() {
        String cipherText = cipherWith(validKey()).encrypt("6222021234567890123");
        PayoutFieldCipher otherKeyCipher = cipherWith(validKey() + "==");

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> otherKeyCipher.decrypt(cipherText));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
    }

    @Test
    void decrypt_withTamperedCiphertext_throws_doesNotReturnGarbage() {
        PayoutFieldCipher cipher = cipherWith(validKey());
        String encrypted = cipher.encrypt("6222021234567890123");
        // 篡改密文尾部：GCM 认证标签校验必须失败
        String tampered = encrypted.substring(0, encrypted.length() - 2)
                + (encrypted.endsWith("A") ? "BB" : "AA");

        assertThrows(ResponseStatusException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void encrypt_withoutKey_throws_doesNotReturnPlaintext() {
        PayoutFieldCipher cipher = cipherWith(null);

        assertFalse(cipher.isReady());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> cipher.encrypt("6222021234567890123"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        assertTrue(e.getReason().contains("密钥"), "报错须点明是密钥问题，理由=" + e.getReason());
    }

    @Test
    void encrypt_withWrongLengthKey_throws() {
        // 16 字节（AES-128）不满足 AES-256 要求 ⇒ isReady=false ⇒ 加密抛错
        PayoutFieldCipher cipher = cipherWith(Base64.getEncoder().encodeToString(new byte[16]));

        assertFalse(cipher.isReady());
        assertThrows(ResponseStatusException.class, () -> cipher.encrypt("123456"));
    }

    @Test
    void decrypt_malformedCiphertext_throws() {
        PayoutFieldCipher cipher = cipherWith(validKey());

        // 缺 iv 段
        assertThrows(ResponseStatusException.class, () -> cipher.decrypt("no-separator"));
        // 空 iv
        assertThrows(ResponseStatusException.class, () -> cipher.decrypt(":abcd"));
    }

    @Test
    void mask_keepsLastFourAndHidesRest() {
        PayoutFieldCipher cipher = cipherWith(validKey());

        assertEquals("****0123", cipher.mask("6222021234567890123"));
        assertEquals("****", cipher.mask("1234"), "长度不足 4 位应全掩码");
        // 保留<b>末 4 位</b>（不是 3 位）：123456789 → 6789
        assertEquals("****6789", cipher.mask("123456789"));
        assertEquals("****4567", cipher.mask("1234567"), "整 7 位：末 4 位 + 3 星号");
        assertNull(cipher.mask(null));
        assertNull(cipher.mask(""));
    }

    @Test
    void mask_isNotReversible_plaintextNotRecoverable() {
        PayoutFieldCipher cipher = cipherWith(validKey());
        String plain = "6222021234567890123";

        String masked = cipher.mask(plain);

        assertNotEquals(plain, masked);
        assertFalse(masked.contains("622202"), "掩码不得含账号前段");
        // 掩码不可逆：不同账号可能同掩码（后 4 位相同）⇒ 不能靠掩码反查
        assertEquals(masked, cipher.mask("999999999999990123"));
    }

    @Test
    void demoSentinelPlaceholder_passesThroughUnencrypted() {
        PayoutFieldCipher cipher = cipherWith(validKey());
        String sentinel = "DEMO-ENCRYPTED-PLACEHOLDER";

        // 演示占位符原样保留，便于识别「这条不是真密文」
        assertEquals(sentinel, cipher.encrypt(sentinel));
        assertEquals(sentinel, cipher.decrypt(sentinel));
    }

    @Test
    void encrypt_withNullOrEmpty_returnsNull() {
        PayoutFieldCipher cipher = cipherWith(validKey());

        assertNull(cipher.encrypt(null));
        assertNull(cipher.encrypt(""));
        assertNull(cipher.decrypt(null));
    }

    @Test
    void isReady_reflectsKeyValidity() {
        assertTrue(cipherWith(validKey()).isReady());
        assertFalse(cipherWith("").isReady());
        assertFalse(cipherWith("   ").isReady());
        assertFalse(cipherWith("not-base64!!").isReady());
    }
}
