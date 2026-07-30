package com.aicabinet.common.dto;

import java.util.List;

public record OpsUserDeviceScopeDto(
        Long userId,
        String scopeMode,
        List<String> deviceIds
) {}
