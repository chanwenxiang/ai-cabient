package com.aicabinet.common.dto;

public record AccountDto(
        Long userId,
        String phoneNumber,
        String name,
        String nickname,
        boolean wechatUser,
        int balanceCents,
        int frozenCents,
        int availableCents,
        boolean verified,
        boolean operator,
        boolean payscoreEnabled,
        boolean alipayAgreementEnabled,
        boolean passwordFreeReady
) {}
