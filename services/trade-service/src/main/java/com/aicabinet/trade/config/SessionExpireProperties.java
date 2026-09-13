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
        if (openingSeconds <= 0) openingSeconds = 90;
        if (restockShoppingMinutes <= 0) restockShoppingMinutes = 30;
        if (consumerDoorOpenMinutes <= 0) consumerDoorOpenMinutes = 10;
        if (recognizingMinutes <= 0) recognizingMinutes = 10;
    }

    public static SessionExpireProperties defaults() {
        return new SessionExpireProperties(90, 30, 10, 10);
    }
}
