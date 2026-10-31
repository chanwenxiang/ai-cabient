package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.SkuCatalog;

/**
 * 「这个 SKU 还在卖吗」判据唯一出处（在架口径拍板 2026-10-30，台账 #367）。
 *
 * <p>在架 = {@code sku_catalog.status=ACTIVE}（忽略大小写），是**档案态**；它与账面量
 * （{@code device_sku_inventory.quantity}、{@link SkuStockLevel}）是两条正交判据：
 * 下架只改档案、不清账面，所以「账面 &gt; 0」既不代表在售，「账面 = 0」也不代表该补货。</p>
 *
 * <p>{@link #isDelisted} 与 {@link #isOnShelf} 刻意不是严格互补：状态为空或其它值时，
 * 在售面按「非 ACTIVE 即不进缺货/低库存/洞察」处理，诊断与评审侧沿用历史语义「只认 INACTIVE」，
 * 免得收口时顺手改掉 2026-10-21 拍板的评审范围。</p>
 *
 * <p>收口（2026-10-31）前 16 个服务类里散着 19 份内联副本，其中 6 份用大小写敏感的
 * {@code equals}（在售商品集合、将邑在售面下发、将邑上架校验、单柜定价）。写路径
 * {@code OpsCatalogAdminService} 直接存 {@code request.status()}、不做 {@code toUpperCase}，
 * 所以「库里是 active 小写」时两拨判据本来就会给出相反答案 ⇒ 统一成忽略大小写。</p>
 *
 * <p>🔴 判据在 SQL 侧还有一份无法共用本函数的副本，它自己也已是单源：
 * {@code DeviceSkuInventoryMapper.xml} 的 sql 片段 {@code onShelfSkuOnly}
 * （{@code EXISTS (SELECT 1 FROM sku_catalog s WHERE s.sku_id = i.sku_id AND s.status = 'ACTIVE')}），
 * 四条低库存查询经 {@code lowStockPredicate} 引用它。动这条判据必须 Java + XML 两处一起动。</p>
 */
public final class SkuOnShelf {

    private static final String ON_SHELF = "ACTIVE";
    private static final String DELISTED = "INACTIVE";

    private SkuOnShelf() {}

    /** 在售：档案态 ACTIVE。{@code sku} 为 null 或状态缺失都不算在售。 */
    public static boolean isOnShelf(SkuCatalog sku) {
        return sku != null && ON_SHELF.equalsIgnoreCase(sku.getStatus());
    }

    /** 已下架：档案态 INACTIVE。状态缺失视为「未标下架」，与历史评审语义一致。 */
    public static boolean isDelisted(SkuCatalog sku) {
        return sku != null && DELISTED.equalsIgnoreCase(sku.getStatus());
    }
}
