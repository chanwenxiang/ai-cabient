package com.aicabinet.common.dto;

import java.util.List;

public record DeviceVisionContextDto(
        String deviceId,
        List<SkuVisionContextItemDto> skus
) {}
