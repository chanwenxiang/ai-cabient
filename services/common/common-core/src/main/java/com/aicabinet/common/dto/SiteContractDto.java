package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record SiteContractDto(
        Long contractId,
        String deviceId,
        String deviceName,
        String siteName,
        String address,
        String landlordName,
        String landlordPhone,
        LocalDate startDate,
        LocalDate endDate,
        int monthlyFeeCents,
        String status,
        String remark,
        Instant updatedAt
) {}
