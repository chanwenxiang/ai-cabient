package com.aicabinet.common.dto;

import java.time.Instant;

/** 账务明细行（财务类型流水） */
public record FundLedgerEntryDto(
        String entryId,
        String financialType,
        String direction,
        long amountCents,
        String merchantId,
        String merchantName,
        String deviceId,
        String orderId,
        String paymentId,
        String channel,
        Instant createdAt
) {}
