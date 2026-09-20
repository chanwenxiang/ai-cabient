package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * OTA 升级进度（O2）。
 *
 * @param deviceId        设备 ID
 * @param appVersion      设备当前实际运行的版本（可能为 null：从未上报过）
 * @param targetVersion   本次升级的目标版本；无升级中任务时为 null
 * @param upgradeStatus   IDLE / DOWNLOADING / INSTALLING / SUCCESS / FAILED
 * @param progressPercent 进度百分比 0-100
 * @param errorMessage    失败原因；仅 FAILED 有值
 * @param reportedAt      首次上报时间
 * @param updatedAt       最近一次进度上报时间
 */
public record OtaUpgradeProgressDto(
        String deviceId,
        String appVersion,
        String targetVersion,
        String upgradeStatus,
        int progressPercent,
        String errorMessage,
        Instant reportedAt,
        Instant updatedAt
) {}
