package com.aicabinet.common.dto;

public record DevicePolicyDto(
        String deviceId,
        boolean salesLocked,
        boolean priceLocked,
        boolean skuEditForbidden,
        boolean saleForbidden
) {}
