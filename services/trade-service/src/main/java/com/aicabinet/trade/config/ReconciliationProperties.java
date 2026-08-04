package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.reconciliation")
public record ReconciliationProperties(
        boolean mockEnabled,
        String wechatBillType,
        boolean scheduledEnabled,
        String scheduledCron
) {}
