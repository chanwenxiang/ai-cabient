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
        if (openingSeconds <= 0) openingSeconds = com.aicabinet.common.constants.SessionTimeouts.TRADE_OPENING_EXPIRE_SECONDS;
        // MA-281：openingSeconds 达到 watchdog 判死地板 ⇒ 60~70s 兜账窗口消失，装配期失败。
        com.aicabinet.common.constants.SessionTimeouts.requireTradeBelowWatchdog(openingSeconds);
        if (restockShoppingMinutes <= 0) restockShoppingMinutes = 30;
        if (consumerDoorOpenMinutes <= 0) consumerDoorOpenMinutes = 10;
        if (recognizingMinutes <= 0) recognizingMinutes = 10;
    }

    public static SessionExpireProperties defaults() {
        // MA-281：兜底默认与 SessionTimeouts.TRADE_OPENING_EXPIRE_SECONDS 同源（原 90 是
        // 2026-11-02 拍板 10s 时代的遗留，2026-11-12 真机拍板后 yml 默认已改 60，这里没跟上）。
        return new SessionExpireProperties(
                com.aicabinet.common.constants.SessionTimeouts.TRADE_OPENING_EXPIRE_SECONDS, 30, 10, 10);
    }
}
