package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record MerchantSettlementBatchDto(
        String batchNo,
        String merchantId,
        String merchantName,
        LocalDate settleAfter,
        Instant settledAt,
        long orderCount,
        long grossCents,
        long platformCents,
        long merchantCents,
        long settledCents,
        long pendingCents,
        long failedCount,
        String batchStatus
) {}
