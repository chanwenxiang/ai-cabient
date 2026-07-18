package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 消费者/运营发起订单退款。 */
public record OrderRefundRequest(
        @NotBlank @Size(max = 256) String reason,
        List<Long> evidenceFileIds
) {}
