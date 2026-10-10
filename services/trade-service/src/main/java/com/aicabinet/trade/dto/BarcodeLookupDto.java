package com.aicabinet.trade.dto;

/**
 * 商品条码官方资料查询结果（CB-025，商品建档「查官方资料」）。
 *
 * @param found        编码中心是否登记该条码；false 时其余字段为空
 * @param name         官方产品名称（如「农夫山泉饮用天然水」）
 * @param brand        品牌
 * @param spec         规格（优先 specification，缺省回退 net_content）
 * @param manufacturer 生产厂商
 * @param category     官方分类（含分类编号原文，不做映射）
 * @param imageUrl     官方商品图（首个；来源站外，展示用，不直接落库为主图）
 * @param message      登记状态说明原文（如「该商品条码已在中国物品编码中心注册…」）
 */
public record BarcodeLookupDto(
        boolean found,
        String name,
        String brand,
        String spec,
        String manufacturer,
        String category,
        String imageUrl,
        String message) {

    /** 编码中心未登记：仅回条码状态说明，其余为空。 */
    public static BarcodeLookupDto notFound(String message) {
        return new BarcodeLookupDto(false, null, null, null, null, null, null, message);
    }
}
