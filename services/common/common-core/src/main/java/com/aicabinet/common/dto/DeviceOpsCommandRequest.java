package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record DeviceOpsCommandRequest(
        /** OPEN_DOOR | LOCK | UNLOCK | REBOOT | SET_TEMP */
        @NotBlank String command,
        String reason,
        /** SET_TEMP 时必填，单位 °C */
        Integer targetTempC
) {
    public DeviceOpsCommandRequest(String command, String reason) {
        this(command, reason, null);
    }
}
