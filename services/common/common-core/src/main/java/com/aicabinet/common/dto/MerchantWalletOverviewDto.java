package com.aicabinet.common.dto;

import java.util.List;

public record MerchantWalletOverviewDto(
        boolean bound,
        String merchantId,
        String merchantName,
        Long balanceCents,
        Long frozenCents,
        Long availableCents,
        /** CB-035：已入钱包但未到 T+1 可提现日（settleAfter>今日）的金额；与提现闸门同口径 */
        Long pendingSettleCents,
        List<MerchantWalletLedgerDto> recentLedgers,
        List<MerchantWithdrawRequestDto> recentWithdraws
) {}
