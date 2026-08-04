package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransferOpsExceptionRequest(
        @NotNull Long assigneeUserId,
        @Size(max = 500) String reason
) {}
