package com.aicabinet.common.dto;

public record UpsertDeviceRequest(
        /** 已废弃：编号由系统自动分配 12 位纯数字，传入将被拒绝。 */
        String deviceId,
        String deviceName,
        String deviceType,
        String merchantId
) {}
