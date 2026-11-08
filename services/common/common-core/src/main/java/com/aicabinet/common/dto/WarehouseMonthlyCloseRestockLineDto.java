package com.aicabinet.common.dto;

/**
 * 月结「上柜」列的柜机下钻一行——件数，不是金额。
 *
 * <p>骨架取 {@code warehouse_movement} 的 {@code OUTBOUND_SHIP} / {@code OUTBOUND_CANCEL} 流水
 * （与月结 restockQty 同源：发运记正、作废记负，逐条相加必等于该 SKU 行的领货净额），
 * 柜机归属与实收差额由 {@code warehouse_outbound_line} 按 {@code (outboundId, skuId, batchNo)} 回填
 * ——发运时一行流水对应一条出库行（{@code WarehouseService.doShipOutbound} 逐 line 写流水），故可精确配对。
 *
 * <p>CB-074（2026-10-29）：月结这一列历来是**领货量**（从分仓带出去多少），不是进柜数量。
 * 现在把三个数一起摆出来，差额不再靠「全量签收」被洗掉：{@code qty = shelvedQty + remainderQty}。
 *
 * @param deviceId      柜机 ID；出库行缺失或无柜机时为 null（明细仍出账，归属留空）
 * @param deviceName    柜机名称；查不到时回退 deviceId
 * @param skuId         商品 ID
 * @param skuName       商品名称
 * @param barcode       69 条码，未配置为 null
 * @param qty           领货净额（同柜同商品在本账期内跨多张出库单合并，整单作废未签收按负行抵扣；月结列即此值）
 * @param shelvedQty    上柜实收件数（= qty − remainderQty；出库行缺失时按领货量兜底，不谎报差额）
 * @param remainderQty  领而未上件数（= qty − shelvedQty；&gt;0 表示货仍在补货员手上，未进柜；作废按同额回退）
 * @param outboundIds   参与汇总的出库单号（顿号分隔，含被作废单，便于回溯单据）
 * @param lastShippedAt 最晚一次发运流水时间（作废回库不更新此列）
 */
public record WarehouseMonthlyCloseRestockLineDto(
        String deviceId,
        String deviceName,
        String skuId,
        String skuName,
        String barcode,
        int qty,
        int shelvedQty,
        int remainderQty,
        String outboundIds,
        String lastShippedAt
) {}
