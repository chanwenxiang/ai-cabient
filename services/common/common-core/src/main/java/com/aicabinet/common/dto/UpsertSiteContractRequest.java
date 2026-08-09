package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record UpsertSiteContractRequest(
        @NotBlank String siteName,
        String address,
        String landlordName,
        String landlordPhone,
        LocalDate startDate,
        LocalDate endDate,
        int monthlyFeeCents,
        String remark
) {}
