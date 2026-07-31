package com.aicabinet.common.dto;

/** 投放地图打点 */
public record DeviceMapPointDto(
        String deviceId,
        String deviceName,
        String merchantId,
        String onlineStatus,
        String lifecycleStatus,
        String routeCode,
        boolean salesLocked,
        Double latitude,
        Double longitude,
        String address
) {}
