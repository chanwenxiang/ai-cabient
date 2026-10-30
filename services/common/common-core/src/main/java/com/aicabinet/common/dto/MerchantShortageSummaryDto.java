package com.aicabinet.common.dto;

/**
 * 商户端「缺货巡柜」聚合行（P2 口径统一，2026-10-15）：
 * 与 admin 缺货看板/补货建议同源，按柜型分流（CB-076）：
 * · 有货道柜 ⇒ {@code DeviceSlotService.isSlotShortage} 货道级判定，shortageQty = Σ 货道 headroom
 * · 开门柜（判据=将邑绑定）⇒ 台账 SKU 级两档（缺货=可售≤0；低库存=0<可售≤low_threshold），
 *   shortageSlots 语义为「待补商品数」，shortageQty = Σmax(容量−可售, 0)
 */
public record MerchantShortageSummaryDto(
        String deviceId,
        String deviceName,
        int shortageSlots,
        int shortageQty) {}
