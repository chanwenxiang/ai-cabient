package com.aicabinet.common.dto;

public record DeviceOpsCommandResultDto(
        String deviceId,
        String command,
        String commandId,
        String message,
        boolean salesLocked
) {}
