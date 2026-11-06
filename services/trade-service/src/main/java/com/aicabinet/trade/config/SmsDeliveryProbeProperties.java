package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信到达率巡检（C 档修订形态，见 scripts/sms-delivery-report.mjs 头注）。
 * 🔴 enabled 默认 false（fail-closed）：阿里云回执只保留最近几天，且当前无真实发送量，
 * 先不自动外呼；要开就在参数配置页开（优先级高于本 env 兜底）。
 */
@ConfigurationProperties(prefix = "aicabinet.sms-delivery-probe")
public record SmsDeliveryProbeProperties(
        boolean enabled,
        int failAlertPercent,
        int lookbackDays,
        int minSample
) {
    public SmsDeliveryProbeProperties {
        if (failAlertPercent <= 0) {
            failAlertPercent = 20;
        }
        if (lookbackDays <= 0) {
            lookbackDays = 3;
        }
        if (minSample <= 0) {
            minSample = 5;
        }
    }
}
