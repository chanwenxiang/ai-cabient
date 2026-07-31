package com.aicabinet.common.dto;

import java.util.List;

public record MerchantWalletOverviewDto(
        boolean bound,
        String merchantId,
        String merchantName,
        Long balanceCents,
        Long frozenCents,
        Long availableCents,
        List<MerchantWalletLedgerDto> recentLedgers,
        List<MerchantWithdrawRequestDto> recentWithdraws
) {}
