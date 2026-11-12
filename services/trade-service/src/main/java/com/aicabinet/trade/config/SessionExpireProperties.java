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
        // 2026-11-02 拍板 10s 的意图是「门锁没开=设备问题」；2026-11-12 真机实证弱网下设备响应
        // 迟到（WS 回执 14.3s / HTTP 上报 2m13s）会误杀真开门，配合 WS 回执救援放宽到 60s
        // （application.yml 同名键优先，这里只是兜底默认）。
        if (openingSeconds <= 0) openingSeconds = 60;
        if (restockShoppingMinutes <= 0) restockShoppingMinutes = 30;
        if (consumerDoorOpenMinutes <= 0) consumerDoorOpenMinutes = 10;
        if (recognizingMinutes <= 0) recognizingMinutes = 10;
    }

    public static SessionExpireProperties defaults() {
        return new SessionExpireProperties(90, 30, 10, 10);
    }
}
