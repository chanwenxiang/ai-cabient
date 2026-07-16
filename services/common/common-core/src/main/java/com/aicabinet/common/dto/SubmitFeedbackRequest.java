package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitFeedbackRequest(
        @NotBlank @Size(max = 32) String feedbackType,
        @NotBlank @Size(max = 2000) String content,
        @Size(max = 128) String contactInfo,
        @Size(max = 64) String deviceId,
        @Size(max = 32) String sessionId,
        @Min(1) @Max(5) Integer rating
) {}
