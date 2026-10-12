package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提现审核（通过/驳回）请求体。
 * 语义收紧：approve 缺失不再按「驳回」兜底，改为 400（fail-closed）——
 * 旧实现 Boolean.TRUE.equals(null) 把漏传当成驳回，是静默危险行为。
 */
public record WithdrawReviewRequest(
        @NotNull Boolean approve,
        @Size(max = 200) String remark
) {}
