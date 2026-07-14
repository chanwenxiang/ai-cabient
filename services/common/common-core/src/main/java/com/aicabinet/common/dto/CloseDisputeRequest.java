package com.aicabinet.common.dto;

import jakarta.validation.constraints.Size;

public record CloseDisputeRequest(
        @Size(max = 512) String note
) {}
