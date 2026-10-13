package com.aicabinet.common.dto;

/**
 * 外部渠道导入解析后的统一行（统一 9 列的行级形态，CB-036）。
 *
 * <p>9 列 = 渠道 / 账期 / 线长 / 操作类型 / 商品ID / 商品名称 / 数量 / 单价 / 时间；
 * 渠道、账期、操作类型在批次级，行级只留差异字段。自己的设计判断（无公开规格，铁律 48）。
 *
 * @param person          线长/领货人（MANUAL 渠道为批次覆盖值）
 * @param extKey          外部商品键（商品ID 或归一化名称）
 * @param extName         外部商品名称
 * @param qty             数量（已按 qtySemantics 折算为正数）
 * @param unitPriceCents  单价（分，可空）
 * @param happenedAt      业务日期（yyyy-MM-dd，可空——嗨便利等无日期列时由导入日兜底）
 * @param srcLine         原始文件行号（留痕核对）
 */
public record ExternalParsedRowDto(
        String person,
        String extKey,
        String extName,
        int qty,
        Long unitPriceCents,
        String happenedAt,
        int srcLine) {
}
