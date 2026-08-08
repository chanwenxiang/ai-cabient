package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 定时任务管理项。
 *
 * @param taskKey        任务标识
 * @param taskName       任务名称
 * @param taskGroup      任务分组
 * @param scheduleDesc   调度说明
 * @param enabled        是否启用
 * @param lastRunAt      最近一次执行时间
 * @param lastResult     最近一次结果（SUCCESS/FAILED/SKIPPED）
 * @param lastMessage    最近一次结果说明
 * @param lastDurationMs 最近一次执行耗时（毫秒）
 * @param remark         备注
 */
public record ScheduledTaskDto(
        String taskKey,
        String taskName,
        String taskGroup,
        String scheduleDesc,
        boolean enabled,
        Instant lastRunAt,
        String lastResult,
        String lastMessage,
        Long lastDurationMs,
        String remark
) {}
