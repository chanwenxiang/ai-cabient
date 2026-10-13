package com.aicabinet.common.dto;

import java.util.List;

/** 补货员从所属分仓自主发起补货（无审批，直接生成出库+补货任务）。 */
public record MerchantSelfReplenishRequest(
        String deviceId,
        String notes,
        List<MerchantSelfReplenishLine> lines
) {}
