package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpsExceptionNoteRequest(
        @NotBlank @Size(max = 500) String note
) {}
