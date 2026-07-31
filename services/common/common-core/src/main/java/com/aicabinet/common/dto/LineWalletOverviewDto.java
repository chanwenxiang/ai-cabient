package com.aicabinet.common.dto;

import java.util.List;

public record LineWalletOverviewDto(
        boolean bound,
        Long managerId,
        String managerName,
        String phone,
        Long balanceCents,
        Long frozenCents,
        Long availableCents,
        List<LineWalletLedgerDto> recentLedgers,
        List<LineWithdrawRequestDto> recentWithdraws
) {}
