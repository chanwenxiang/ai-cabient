package com.aicabinet.common.dto;

import java.util.List;

public record DeviceTempPlanDto(
        String deviceId,
        boolean enabled,
        List<DeviceTempPlanEntryDto> entries
) {}
