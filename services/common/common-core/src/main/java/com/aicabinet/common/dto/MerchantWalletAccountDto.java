package com.aicabinet.common.dto;

public record MerchantWalletAccountDto(
        String merchantId,
        String merchantName,
        String contactPhone,
        String status,
        Long balanceCents,
        Long frozenCents,
        Long availableCents
) {}
