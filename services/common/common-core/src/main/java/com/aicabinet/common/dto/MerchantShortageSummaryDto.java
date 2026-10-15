package com.aicabinet.common.dto;

/**
 * 商户端「缺货巡柜」聚合行（P2 口径统一，2026-10-15）：
 * 与 admin 缺货看板/补货建议同源（DeviceSlotService.isSlotShortage 货道级判定）。
 * shortageQty = Σ 货道 headroom（补到 par、受 maxLevel 限幅），与建议页口径一致。
 */
public record MerchantShortageSummaryDto(
        String deviceId,
        String deviceName,
        int shortageSlots,
        int shortageQty) {}
