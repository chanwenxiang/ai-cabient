package com.aicabinet.common.dto;

import java.util.List;

/**
 * 补货员从所属分仓自主发起补货（无审批，直接生成出库+补货任务）。
 *
 * clientRequestNo（可选，2026-10-23 审计残留 H2 收口）：客户端幂等键。
 * 同一提交意图的超时重试**复用同键**，服务端按键幂等返回原任务；
 * 新的提交意图必须换新键。未传时服务端回退 90s 短窗去重（旧客户端兼容）。
 */
public record MerchantSelfReplenishRequest(
        String deviceId,
        String notes,
        List<MerchantSelfReplenishLine> lines,
        String clientRequestNo
) {}
