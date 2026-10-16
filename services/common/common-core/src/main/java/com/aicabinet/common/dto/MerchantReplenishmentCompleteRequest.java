package com.aicabinet.common.dto;

/**
 * 商户补货完成附加信息（CB-050 收尾，2026-10-16）：可选定位，写入完成审计，
 * 与盘点凭证定位同语义——证明"人到柜前"（非 AI 识别）。
 */
public record MerchantReplenishmentCompleteRequest(
        Double latitude,
        Double longitude
) {}
