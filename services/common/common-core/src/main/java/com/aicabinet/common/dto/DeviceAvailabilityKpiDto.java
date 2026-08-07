package com.aicabinet.common.dto;

import java.time.LocalDate;

/**
 * 设备可用性 KPI 日快照。
 *
 * @param kpiDate               统计日期
 * @param deviceTotal           期末设备总数
 * @param offlineEvents         当日离线事件数（DEVICE_OFFLINE）
 * @param autoLockCount         当日离线自动锁机台次（DEVICE_FAULT）
 * @param autoUnlockCount       当日稳定在线自动解锁台次
 * @param manualUnlockCount     当日人工解锁台次（运维按钮 / 维修工单完结）
 * @param avgLockHours          当日锁机事件平均锁定时长（小时，未解决为空）
 * @param avgRecoverHours       当日离线事件平均恢复时长（小时，未恢复为空）
 * @param manualInterventionRate 人工解锁占比（人工/（人工+自动））
 */
public record DeviceAvailabilityKpiDto(
        LocalDate kpiDate,
        int deviceTotal,
        int offlineEvents,
        int autoLockCount,
        int autoUnlockCount,
        int manualUnlockCount,
        Double avgLockHours,
        Double avgRecoverHours,
        Double manualInterventionRate
) {}
