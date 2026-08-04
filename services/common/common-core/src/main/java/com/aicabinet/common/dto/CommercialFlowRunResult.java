package com.aicabinet.common.dto;

import java.util.List;

public record CommercialFlowRunResult(
        String deviceId,
        String skuId,
        String sessionId,
        String orderId,
        Long routeId,
        Long outboundId,
        Long reconciliationId,
        List<CommercialFlowStepDto> steps
) {}
