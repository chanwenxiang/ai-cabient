package com.aicabinet.common.dto;

/** 日资金账单（账期汇总） */
public record FundDailyBillDto(
        String bizDate,
        String merchantId,
        String merchantName,
        long orderPaidCents,
        long platformFeeCents,
        long channelFeeCents,
        /** CB-020③：通道费口径来源——{@link #SOURCE_ACTUAL}=对账账单实结分摊，{@link #SOURCE_ESTIMATED}=按配置 bps 估算。 */
        String channelFeeSource,
        long creditedCents,
        long pendingCents,
        long orderCount,
        boolean solidified
) {
    public static final String SOURCE_ACTUAL = "ACTUAL";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";
}
