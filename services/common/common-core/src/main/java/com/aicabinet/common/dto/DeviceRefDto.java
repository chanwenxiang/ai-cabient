package com.aicabinet.common.dto;

/** 设备基础信息只读（供补货/仓库等履约角色选择与展示，不含敏感运维字段）。 */
public record DeviceRefDto(
        String deviceId,
        String deviceName,
        String onlineStatus,
        String merchantId
) {}
