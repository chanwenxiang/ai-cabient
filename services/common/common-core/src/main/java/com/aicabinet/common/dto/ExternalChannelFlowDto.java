package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 外部渠道流水（CB-036）：批次确认后的聚合事实（仓×SKU×渠道×操作类型）；月结取数源。
 *
 * @param flowId          流水 ID
 * @param batchId         来源批次
 * @param channelCode     渠道码
 * @param periodLabel     账期标签=结束月
 * @param warehouseId     归属仓
 * @param personName      线长/领货人
 * @param skuId           本系统 SKU
 * @param opType          WAREHOUSE_OUT / WAREHOUSE_STOCKTAKE / GUEST_WATER
 * @param qty             数量
 * @param unitPriceCents  单价（分；组内不一致时为 null）
 * @param happenedMin     最早业务日期
 * @param happenedMax     最晚业务日期
 * @param rowCount        聚合原始行数
 * @param remark          备注
 * @param createdAt       落库时间
 */
public record ExternalChannelFlowDto(
        Long flowId,
        Long batchId,
        String channelCode,
        String periodLabel,
        String warehouseId,
        String personName,
        String skuId,
        String opType,
        int qty,
        Long unitPriceCents,
        LocalDate happenedMin,
        LocalDate happenedMax,
        int rowCount,
        String remark,
        Instant createdAt) {
}
