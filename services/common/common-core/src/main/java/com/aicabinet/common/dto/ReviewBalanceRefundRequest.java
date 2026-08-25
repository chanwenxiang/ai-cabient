package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewBalanceRefundRequest(
        @NotNull Boolean approve,
        @Size(max = 200) String remark
) {}
