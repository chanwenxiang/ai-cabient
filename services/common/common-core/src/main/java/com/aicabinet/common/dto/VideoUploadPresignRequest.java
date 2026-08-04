package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VideoUploadPresignRequest(
        @NotBlank String sessionId,
        @NotBlank String deviceId,
        @NotNull Long userId,
        String camera,
        String extension,
        Boolean sim
) {}
