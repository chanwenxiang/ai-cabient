package com.aicabinet.common.dto;

import java.time.Instant;

public record PaymentPlatformBillLineDto(
        Long lineId,
        String platformTradeNo,
        String merchantOrderNo,
        long amountCents,
        Instant tradeTime,
        String tradeType,
        boolean matched
) {}
