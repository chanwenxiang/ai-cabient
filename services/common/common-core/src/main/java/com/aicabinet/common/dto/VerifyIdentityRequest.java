package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyIdentityRequest(
        @NotBlank @Size(min = 2, max = 32) String realName,
        @NotBlank @Pattern(regexp = "\\d{4}", message = "idCardLast4 must be 4 digits") String idCardLast4
) {}
