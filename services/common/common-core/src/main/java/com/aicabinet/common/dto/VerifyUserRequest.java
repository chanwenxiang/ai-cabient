package com.aicabinet.common.dto;

import jakarta.validation.constraints.Size;

public record VerifyUserRequest(
        boolean verified,
        @Size(max = 32) String realName
) {}
