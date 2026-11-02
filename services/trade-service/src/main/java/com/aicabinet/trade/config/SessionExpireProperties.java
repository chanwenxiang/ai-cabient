package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.session-expire")
public record SessionExpireProperties(
        long openingSeconds,
        long restockShoppingMinutes,
        long consumerDoorOpenMinutes,
        long recognizingMinutes
) {
    public SessionExpireProperties {
        // 2026-11-02 用户拍板：开门会话超时 90s → **60s**（扫完码 1 分钟内没开门就取消，
        // 免得用户在门口干等；对应 OPEN_TIMEOUT 异常单的文案同步改）。
        if (openingSeconds <= 0) openingSeconds = 60;
        if (restockShoppingMinutes <= 0) restockShoppingMinutes = 30;
        if (consumerDoorOpenMinutes <= 0) consumerDoorOpenMinutes = 10;
        if (recognizingMinutes <= 0) recognizingMinutes = 10;
    }

    public static SessionExpireProperties defaults() {
        return new SessionExpireProperties(90, 30, 10, 10);
    }
}
