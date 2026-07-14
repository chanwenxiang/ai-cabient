package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveOpsExceptionRequest(@NotBlank String resolution) {}
