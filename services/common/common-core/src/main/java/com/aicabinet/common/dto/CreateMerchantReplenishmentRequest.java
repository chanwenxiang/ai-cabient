package com.aicabinet.common.dto;

import java.util.List;

public record CreateMerchantReplenishmentRequest(
        String deviceId,
        String notes,
        List<CreateMerchantReplenishmentRequestLine> lines,
        List<Long> evidenceFileIds
) {}
