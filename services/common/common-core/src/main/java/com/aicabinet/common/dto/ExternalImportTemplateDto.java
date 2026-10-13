package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 外部渠道解析模板（CB-036，V347）。
 *
 * @param templateId         模板 ID
 * @param channelCode        渠道码（dahuange/haibianli/jianyi/minsheng_wds/leyaoyao/keqing_water）
 * @param channelName        渠道名
 * @param fileType           CSV_GBK / XLSX / XLS
 * @param sheetStrategy      FIRST / ALL
 * @param qtySemantics       DIRECT / BY_DIFF / SALES
 * @param keyField           PRODUCT_ID / NAME
 * @param personSource       SHEET_NAME / COLUMN / FILE_NAME / MANUAL
 * @param opType             WAREHOUSE_OUT / WAREHOUSE_STOCKTAKE / GUEST_WATER
 * @param columnMap          语义字段→外部列头
 * @param defaultWarehouseId MANUAL 渠道兜底仓
 * @param enabled            是否启用
 * @param remark             说明
 * @param updatedAt          更新时间
 */
public record ExternalImportTemplateDto(
        Long templateId,
        String channelCode,
        String channelName,
        String fileType,
        String sheetStrategy,
        String qtySemantics,
        String keyField,
        String personSource,
        String opType,
        Map<String, String> columnMap,
        String defaultWarehouseId,
        boolean enabled,
        String remark,
        Instant updatedAt) {
}
