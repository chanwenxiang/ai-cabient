package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 消息触达渠道开关：默认仅站内信；配置后启用微信订阅消息 / 短信。 */
@ConfigurationProperties(prefix = "aicabinet.notify")
public record NotificationProperties(
        boolean wechatEnabled,
        boolean smsEnabled,
        boolean asyncEnabled
) {
    public static NotificationProperties defaults() {
        return new NotificationProperties(false, false, false);
    }
}
