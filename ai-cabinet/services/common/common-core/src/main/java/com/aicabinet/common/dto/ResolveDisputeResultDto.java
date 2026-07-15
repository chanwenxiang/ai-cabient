package com.aicabinet.common.dto;

/** 争议结案结果：含订单与退/补差信息 */
public record ResolveDisputeResultDto(
        OrderDto order,
        String resolutionType,
        int originalAmountCents,
        int finalAmountCents,
        int adjustmentCents,
        String message
) {}
