package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record MerchantReplenishmentRequestDto(
        Long requestId,
        String merchantId,
        String merchantName,
        String deviceId,
        String deviceName,
        String status,
        String notes,
        Long createdBy,
        String createdByName,
        Instant submittedAt,
        Instant reviewedAt,
        Long reviewerId,
        String reviewerName,
        String rejectReason,
        Long replenishmentTaskId,
        Long outboundId,
        List<MerchantReplenishmentRequestLineDto> lines,
        Integer evidenceCount
) {}
