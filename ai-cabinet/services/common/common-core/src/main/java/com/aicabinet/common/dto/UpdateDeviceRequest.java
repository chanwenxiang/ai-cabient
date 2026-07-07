package com.aicabinet.common.dto;

public record UpdateDeviceRequest(
        String deviceName,
        String deviceType
) {}
