package com.aicabinet.common.dto;

import java.time.Instant;

public record LineWalletLedgerDto(
        Long ledgerId,
        Long managerId,
        String entryType,
        Long amountCents,
        Long balanceAfter,
        Long frozenAfter,
        String refType,
        String refId,
        String remark,
        Instant createdAt
) {}
