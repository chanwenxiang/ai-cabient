package com.aicabinet.common.dto;

/**
 * 商户端「缺货巡柜」聚合行：台账 SKU 级两档（缺货=可售≤0；低库存=0<可售≤low_threshold，
 * 与 {@code SkuStockLevel} / 备货建议 suggestFromLowStock 同源）。
 * shortageSlots 语义为「待补商品数」，shortageQty = Σmax(容量−可售, 0)。
 * 批 3b 第二十刀：货道级分支已整体退场（全柜型统一此口径）。
 */
public record MerchantShortageSummaryDto(
        String deviceId,
        String deviceName,
        int shortageSlots,
        int shortageQty) {}
