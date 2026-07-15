package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.wechat-miniapp")
public record WeChatMiniAppProperties(
        boolean enabled,
        String appId,
        String appSecret
) {
    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }
}
