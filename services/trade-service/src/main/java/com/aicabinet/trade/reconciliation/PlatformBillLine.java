package com.aicabinet.trade.reconciliation;

import java.time.Instant;

public record PlatformBillLine(
        String platformTradeNo,
        String merchantOrderNo,
        long amountCents,
        Instant tradeTime,
        String tradeType,
        String rawDetail
) {}
