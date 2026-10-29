package com.aicabinet.common.dto;

/**
 * 月结「上柜」列的柜机下钻一行——件数，不是金额。
 *
 * <p>骨架取 {@code warehouse_movement} 的 {@code OUTBOUND_SHIP} 流水（与月结 restockQty 同源，
 * 逐条相加必等于该 SKU 行的上柜数），柜机归属由 {@code warehouse_outbound_line} 按
 * {@code (outboundId, skuId, batchNo)} 回填——发运时一行流水对应一条出库行
 * （{@code WarehouseService.ship} 逐 line 写流水），故可精确配对。
 *
 * @param deviceId     柜机 ID；出库行缺失或无柜机时为 null（明细仍出账，归属留空）
 * @param deviceName   柜机名称；查不到时回退 deviceId
 * @param skuId        商品 ID
 * @param skuName      商品名称
 * @param barcode      69 条码，未配置为 null
 * @param qty          上柜件数（同柜同商品在本账期内跨多张出库单已合并）
 * @param outboundIds  参与汇总的出库单号（逗号分隔，便于回溯单据）
 * @param lastShippedAt 最晚一次发运时间（= 流水时间）
 */
public record WarehouseMonthlyCloseRestockLineDto(
        String deviceId,
        String deviceName,
        String skuId,
        String skuName,
        String barcode,
        int qty,
        String outboundIds,
        String lastShippedAt
) {}
