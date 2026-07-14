package com.aicabinet.common.dto;

public record CommercialFlowRunRequest(
        String deviceId,
        String skuId,
        Integer inboundQty,
        Long consumerUserId,
        String channel
) {}
