package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceFaultReportRequest(
        @NotBlank @Size(max = 32) String issueType,
        @Size(max = 512) String description
) {}
