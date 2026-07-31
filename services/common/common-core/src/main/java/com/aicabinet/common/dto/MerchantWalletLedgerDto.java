package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantWalletLedgerDto(
        Long ledgerId,
        String merchantId,
        String entryType,
        Long amountCents,
        Long balanceAfter,
        Long frozenAfter,
        String refType,
        String refId,
        String remark,
        Instant createdAt
) {}
