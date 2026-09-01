package com.aicabinet.common.dto;

/**
 * 销售报表一行（商品 / 货柜 / 商户 / 毛利 / 支付渠道维共用）。
 *
 * @param dimKey           维度编码（SKU / 设备 ID / 商户 ID / 支付渠道码）
 * @param dimLabel         维度名称
 * @param orderCount       订单数
 * @param qty              销量（件数）
 * @param revenueCents     营收（分，行金额合计，含已退部分的原成交）
 * @param cogsCents        成本（分）
 * @param marginCents      毛利（分）= 营收 - 成本
 * @param refundedCents    退款金额（分）；商品维按订单行金额占比分摊
 * @param refundOrderCount 发生过退款的订单数
 */
public record SalesReportRowDto(
        String dimKey,
        String dimLabel,
        long orderCount,
        long qty,
        long revenueCents,
        long cogsCents,
        long marginCents,
        long refundedCents,
        long refundOrderCount
) {
    /** 净营收（分）= 营收 - 退款。 */
    public long netRevenueCents() {
        return Math.max(0L, revenueCents - Math.max(0L, refundedCents));
    }
}
