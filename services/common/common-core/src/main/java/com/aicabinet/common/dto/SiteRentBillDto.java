package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 场地租金应付账单（按账期出账台账，标记已付不触发打款）。
 */
public record SiteRentBillDto(
        Long billId,
        Long contractId,
        String deviceId,
        String siteName,
        String billMonth,
        String partyType,
        String partyId,
        int shareBps,
        int fixedCents,
        int baseFeeCents,
        int amountCents,
        String status,
        Instant paidAt,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {}
