package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

public record UpsertSiteRentSplitRulesRequest(
        @NotEmpty @Valid List<Rule> rules
) {
    public record Rule(
            Long ruleId,
            @NotBlank String partyType,
            String partyId,
            int shareBps,
            int fixedCents,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {}
}
