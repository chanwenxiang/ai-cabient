package com.aicabinet.common.dto;

/**
 * 定时任务手动触发结果。
 *
 * @param taskKey        任务标识
 * @param result         TRIGGERED=已触发 / SKIPPED=已跳过（停用或执行中）
 * @param message        说明（含本次结果说明与耗时）
 * @param lastMessage    任务写入的「最近结果说明」，SKIPPED 时为 null
 * @param lastDurationMs 本次执行耗时（毫秒），SKIPPED 时为 null
 */
public record ScheduledTaskRunResultDto(
        String taskKey,
        String result,
        String message,
        String lastMessage,
        Long lastDurationMs
) {
    public ScheduledTaskRunResultDto(String taskKey, String result, String message) {
        this(taskKey, result, message, null, null);
    }
}
