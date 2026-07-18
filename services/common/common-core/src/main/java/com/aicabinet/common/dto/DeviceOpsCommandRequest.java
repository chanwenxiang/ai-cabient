package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record DeviceOpsCommandRequest(
        /** OPEN_DOOR | LOCK | UNLOCK | REBOOT */
        @NotBlank String command,
        String reason
) {}
