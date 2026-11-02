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
        // 2026-11-02 用户拍板：开门命令 10 秒没反应即判开门失败（yml 同名键优先，这里只是兜底默认）。
        if (openingSeconds <= 0) openingSeconds = 10;
        if (restockShoppingMinutes <= 0) restockShoppingMinutes = 30;
        if (consumerDoorOpenMinutes <= 0) consumerDoorOpenMinutes = 10;
        if (recognizingMinutes <= 0) recognizingMinutes = 10;
    }

    public static SessionExpireProperties defaults() {
        return new SessionExpireProperties(90, 30, 10, 10);
    }
}
