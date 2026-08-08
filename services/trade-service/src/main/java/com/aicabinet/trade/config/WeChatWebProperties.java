package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 微信公众号网页授权（H5 OAuth）配置。 */
@ConfigurationProperties(prefix = "aicabinet.wechat-web")
public record WeChatWebProperties(
        boolean enabled,
        String appId,
        String appSecret
) {
    public boolean isConfigured() {
        return enabled && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }
}
