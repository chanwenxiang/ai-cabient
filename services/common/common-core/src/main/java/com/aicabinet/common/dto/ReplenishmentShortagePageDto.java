package com.aicabinet.common.dto;

import java.util.List;

/** 缺货建议分页结果，附带全量缺货设备 ID（用于一键规划补货）。 */
public record ReplenishmentShortagePageDto(
        List<ReplenishmentShortageRowDto> items,
        int page,
        int size,
        long total,
        List<String> shortageDeviceIds
) {}
