package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 运营关闭待支付订单。 */
public record CancelUnpaidOrderRequest(
        @NotBlank @Size(min = 4, max = 256) String reason,
        /** 关单同时拉黑该用户（可选）。 */
        Boolean blacklist
) {}
