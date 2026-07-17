package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;

public record RedeemPointsRequest(
        @NotNull Long itemId
) {}
