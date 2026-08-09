package com.aicabinet.common.dto;

/**
 * 温控计划条目：从当日 00:00 起的分钟数对应一个目标温度（跨日回绕）。
 */
public record DeviceTempPlanEntryDto(
        Long entryId,
        int startMinute,
        int targetTempC
) {}
