package com.aicabinet.common.dto;

import java.util.List;

public record OpsUserDeviceScopeDto(
        Long userId,
        String scopeMode,
        List<String> deviceIds,
        List<String> routeCodes,
        List<String> allowedDeviceIds
) {
    public OpsUserDeviceScopeDto(Long userId, String scopeMode, List<String> deviceIds, List<String> routeCodes) {
        this(userId, scopeMode, deviceIds, routeCodes, null);
    }

    public OpsUserDeviceScopeDto(Long userId, String scopeMode, List<String> deviceIds) {
        this(userId, scopeMode, deviceIds, List.of(), null);
    }
}
