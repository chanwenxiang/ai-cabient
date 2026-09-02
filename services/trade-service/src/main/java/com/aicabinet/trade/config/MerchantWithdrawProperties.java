package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.merchant-withdraw")
public record MerchantWithdrawProperties(
        boolean mockEnabled,
        long minAmountCents,
        long dailyLimitCents,
        long reviewThresholdCents,
        /** 固定手续费（分），可与 feeBps 叠加 */
        long feeCents,
        /** 手续费万分比，如 50 = 0.5% */
        long feeBps
) {
    public MerchantWithdrawProperties {
        if (minAmountCents <= 0) {
            minAmountCents = 100;
        }
        if (dailyLimitCents <= 0) {
            dailyLimitCents = 500_000;
        }
        if (reviewThresholdCents <= 0) {
            reviewThresholdCents = 50_000;
        }
        if (feeCents < 0) {
            feeCents = 0;
        }
        if (feeBps < 0) {
            feeBps = 0;
        }
    }
}
