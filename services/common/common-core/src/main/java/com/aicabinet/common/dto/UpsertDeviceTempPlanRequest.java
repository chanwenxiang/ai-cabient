package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpsertDeviceTempPlanRequest(
        boolean enabled,
        @NotNull List<DeviceTempPlanEntryDto> entries
) {}
