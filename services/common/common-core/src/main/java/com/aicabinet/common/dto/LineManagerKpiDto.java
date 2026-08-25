package com.aicabinet.common.dto;

import java.time.LocalDate;
import java.util.List;

public record LineManagerKpiDto(
        Long managerId,
        LocalDate from,
        LocalDate to,
        long gmvCents,
        long commissionCents,
        int deviceCount,
        int activeDays,
        List<Daily> dailies
) {
    public record Daily(
            LocalDate bizDate,
            long gmvCents,
            long commissionCents,
            int orderCount
    ) {}
}
