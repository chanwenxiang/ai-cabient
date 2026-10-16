package com.aicabinet.common.dto;

/**
 * 可切换组织（CB-050 扩展，2026-10-16）：绑定商户 + 下级商户树。
 * 切换为前端上下文过滤（后端本就按 allowedMerchantIds 全树放行）。
 */
public record MerchantOrgDto(
        String merchantId,
        String merchantName
) {}
