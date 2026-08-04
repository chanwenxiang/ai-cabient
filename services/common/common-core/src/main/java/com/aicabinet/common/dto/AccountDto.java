package com.aicabinet.common.dto;

public record AccountDto(
        Long userId,
        String phoneNumber,
        int balanceCents,
        int frozenCents,
        int availableCents,
        boolean verified,
        boolean operator,
        String payPreferredChannel,
        boolean payscoreEnabled,
        boolean alipayAgreementEnabled,
        boolean passwordFreeReady
) {}
