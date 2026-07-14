package com.aicabinet.common.dto;

import jakarta.validation.constraints.Size;

public record ReopenDisputeRequest(
        @Size(max = 512) String note,
        String priority
) {}
