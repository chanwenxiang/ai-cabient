package com.aicabinet.common.dto;

/**
 * 定时任务手动触发结果。
 *
 * @param taskKey 任务标识
 * @param result  TRIGGERED=已触发 / SKIPPED=已跳过（停用或执行中）
 * @param message 说明
 */
public record ScheduledTaskRunResultDto(
        String taskKey,
        String result,
        String message
) {}
