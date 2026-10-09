package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 商户月度结算单（CB-020 ②）：{@code merchant_settlement_bill} 快照行。
 * 金额单位：分。
 */
public record MerchantSettlementBillDto(
        String billNo,
        String merchantId,
        String merchantName,
        LocalDate periodMonth,
        String status,
        int orderCount,
        long grossCents,
        long platformCents,
        long merchantCents,
        long settledCents,
        long pendingCents,
        int failedCount,
        Instant computedAt,
        Instant confirmedAt
) {}
