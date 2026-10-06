package com.aicabinet.trade.payout;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 收款方敏感字段（银行账号/支付宝账号）的 AES-256-GCM 加密器。
 *
 * <p><b>为什么用 GCM 而不是 CBC</b>：GCM 带认证标签，能 detect 密文被篡改；CBC 需额外 HMAC 且易踩 padding oracle。
 *
 * <p><b>密文格式</b>：{@code base64(iv):base64(ciphertext+tag)}，每条记录随机 IV（12 字节，GCM 推荐值）。
 *
 * <p>🔴 <b>fail-closed</b>：密钥缺失/长度不对/解密失败，<b>一律抛 500，不返回明文、不降级</b>。
 * 「解不开就当明文存」是资损与合规双重风险。
 *
 * <p>⚠️ <b>密钥轮换代价</b>：本实现无密钥版本号 ⇒ 换密钥后存量密文全部不可解。
 * 真要轮换需改为 {@code v1:keyId:iv:ct} 格式并保留旧密钥解密路径（本期不做，留 TODO 说明）。
 */
@Component
public class PayoutFieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String SEPARATOR = ":";
    /** 明文占位符：仅用于「本就不敏感」的演示值，encrypt 时原样返回，不加密。 */
    private static final String PLAINTEXT_SENTINEL_PREFIX = "DEMO-ENCRYPTED-PLACEHOLDER";

    private final PayoutEncryptionProperties properties;
    private final SecureRandom random = new SecureRandom();

    public PayoutFieldCipher(PayoutEncryptionProperties properties) {
        this.properties = properties;
    }

    public boolean isReady() {
        return properties.isConfigured() && decodeKey() != null;
    }

    /**
     * 加密明文账号。已是占位符则原样返回（演示数据不产生伪密文，便于识别）。
     *
     * @return Base64(iv):Base64(ciphertext+tag)
     * @throws ResponseStatusException 密钥未配置时 500（fail-closed，绝不静默明文落库）
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        if (plaintext.startsWith(PLAINTEXT_SENTINEL_PREFIX)) {
            return plaintext;
        }
        SecretKeySpec key = requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv)
                    + SEPARATOR + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            // 绝不把明文回显到异常信息里
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "收款账号加密失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 解密。仅供**真正要发起打款**的运行时路径使用（列表/详情一律走 mask）。
     *
     * @throws ResponseStatusException 密钥未配置、密文格式错、解密失败 ⇒ 500
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return null;
        }
        if (ciphertext.startsWith(PLAINTEXT_SENTINEL_PREFIX)) {
            return ciphertext;
        }
        SecretKeySpec key = requireKey();
        int idx = ciphertext.indexOf(SEPARATOR);
        if (idx <= 0 || idx == ciphertext.length() - 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "收款账号密文格式非法（缺 iv 或密文体）");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(ciphertext.substring(0, idx));
            byte[] ct = Base64.getDecoder().decode(ciphertext.substring(idx + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM 认证失败也会走这里：密文被篡改 or 密钥不对。两者都不能放行明文。
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "收款账号解密失败（密钥不匹配或密文被篡改）：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 生成掩码供列表展示：保留末 4 位，其余以 {@code *} 替代（长度不足时全掩码）。
     *
     * <p>不可逆：与 {@link #encrypt} 配对使用，DB 里同时存 mask 与密文，查询只读 mask。
     */
    public String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        String trimmed = plaintext.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        String tail = trimmed.substring(trimmed.length() - 4);
        return "****" + tail;
    }

    private SecretKeySpec requireKey() {
        byte[] raw = decodeKey();
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "收款账号加密密钥未配置（aicabinet.payout-encryption.key / "
                            + "AICABINET_PAYOUT_ENCRYPTION_KEY）—— 拒绝以明文落库");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private byte[] decodeKey() {
        String key = properties.key();
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(key.trim());
            return raw.length == PayoutEncryptionProperties.AES_256_KEY_BYTES ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
