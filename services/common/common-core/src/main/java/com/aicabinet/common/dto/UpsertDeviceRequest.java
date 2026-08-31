package com.aicabinet.common.dto;

public record UpsertDeviceRequest(
        /** 留空则系统自动分配 6–10 位纯数字编号（贴码/扫码用）。 */
        String deviceId,
        String deviceName,
        String deviceType,
        String merchantId
) {}
