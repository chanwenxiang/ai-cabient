package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicabinet.line-withdraw")
public record LineWithdrawProperties(
        boolean mockEnabled,
        long minAmountCents,
        /**
         * 单笔上限（分）；<=0 ⇒ 不做单笔拦截（V308 补）。
         *
         * <p>🔴 <b>此前缺失的字段</b>：V307 补了 {@code LINE_WITHDRAW_MAX_CENTS} 的
         * SystemConfig 键与 {@code WithdrawPolicyResolver.lineMaxAmountCents()} 读取，
         * 却<b>没给本 record 加对应字段</b> ⇒ {@code pick(key, ymlValue=0, 0)} 永远取兜底 0，
         * 即「运营台不配就永不限额」。商户侧 {@code MerchantWithdrawProperties} 同期是有的，
         * 两侧不对称。yml 也同步补了 {@code max-amount-cents}。
         */
        long maxAmountCents,
        long dailyLimitCents,
        long reviewThresholdCents,
        /** 固定手续费（分），可与 feeBps 叠加 */
        long feeCents,
        /** 手续费万分比，如 50 = 0.5% */
        long feeBps
) {
    public LineWithdrawProperties {
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
