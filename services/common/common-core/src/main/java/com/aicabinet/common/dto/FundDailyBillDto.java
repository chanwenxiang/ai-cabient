package com.aicabinet.common.dto;

/** 日资金账单（账期汇总） */
public record FundDailyBillDto(
        String bizDate,
        String merchantId,
        String merchantName,
        long orderPaidCents,
        long platformFeeCents,
        long channelFeeCents,
        long creditedCents,
        long pendingCents,
        long orderCount,
        boolean solidified
) {}
