package com.aicabinet.common.dto;

import java.util.List;

public record PaymentReconciliationDetailDto(
        PaymentReconciliationDto summary,
        String detailJson,
        List<PaymentPlatformBillLineDto> lines
) {}
