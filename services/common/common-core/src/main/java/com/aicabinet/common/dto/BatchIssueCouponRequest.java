package com.aicabinet.common.dto;

import java.util.List;

public record BatchIssueCouponRequest(
    Long couponDefId,
    List<Long> userIds
) {}
