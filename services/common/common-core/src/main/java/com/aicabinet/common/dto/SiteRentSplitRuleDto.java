package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record SiteRentSplitRuleDto(
        Long ruleId,
        Long contractId,
        String partyType,
        String partyId,
        int shareBps,
        int fixedCents,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Instant updatedAt
) {}
