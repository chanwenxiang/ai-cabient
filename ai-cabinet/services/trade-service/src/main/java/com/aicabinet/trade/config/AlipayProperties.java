package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.alipay")
public record AlipayProperties(
        boolean enabled,
        String appId,
        String privateKey,
        String alipayPublicKey,
        String gatewayUrl,
        String notifyUrl
) {
    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank()
                && privateKey != null && !privateKey.isBlank()
                && alipayPublicKey != null && !alipayPublicKey.isBlank();
    }
}
