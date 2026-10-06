package com.aicabinet.trade.payout;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提现收款方敏感字段的加密配置。
 *
 * <p>🔴 <b>密钥来源纪律</b>：密钥只能来自环境变量 {@code AICABINET_PAYOUT_ENCRYPTION_KEY}（经
 * {@code aicabinet.payout-encryption.key} 绑定）。<b>不提供任何默认密钥</b>，缺失时
 * {@link PayoutFieldCipher} 直接 fail-fast 抛错——绝不允许「无密钥 ⇒ 明文落库」这种 fail-open。
 *
 * <p>密钥格式：Base64 编码的 32 字节（AES-256）。生成方式：
 * <pre>{@code openssl rand -base64 32}</pre>
 */
@ConfigurationProperties(prefix = "aicabinet.payout-encryption")
public record PayoutEncryptionProperties(
        /** Base64 的 32 字节 AES 密钥；为空 ⇒ 启动即失败（不降级为明文）。 */
        String key
) {
    public static final int AES_256_KEY_BYTES = 32;

    public PayoutEncryptionProperties {
        // 不在此处校验长度：构造期抛错会让 Spring 启动失败信息晦涩。
        // 实际校验在 PayoutFieldCipher#resolveKey（首次使用时给出可操作报错）。
    }

    public boolean isConfigured() {
        return key != null && !key.isBlank();
    }
}
