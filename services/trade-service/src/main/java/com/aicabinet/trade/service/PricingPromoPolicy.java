package com.aicabinet.trade.service;

/**
 * 定价促销策略快照（F1 动态定价：时段折扣 + 库存清仓折扣）。
 *
 * <p><b>为什么要快照而不是每算一次价就查一次配置</b>：{@code resolveUnitPriceCents} 是在
 * 「商品目录」「结算明细」这类**循环里**逐个 SKU 调用的，而系统配置是直读 DB、
 * 无缓存（刻意如此，保证运营改完即时生效）。若在方法内逐键读取，一次目录查询就会
 * 变成 {@code SKU 数 × 键数} 次查库。故由 {@link MerchantSkuPricingService#loadPromoPolicy()}
 * 读一次快照，调用方在循环外取、循环内复用。
 *
 * <p><b>归一化即 fail-closed，且两个维度互不连坐</b>：任何越界/自相矛盾的配置都收敛成
 * 「该维度不生效」，而不是猜一个近似值。写错配置的后果是「折扣没生效（原价成交）」，
 * 绝不会是「算出 0 元」或「倒贴钱」。时段与清仓**各自独立归一化** ——
 * 时段配错（如 start == end 的空窗口）不会连带关掉清仓，反之亦然。
 */
public record PricingPromoPolicy(
        boolean timeWindowEnabled,
        int startHour,
        int endHour,
        int discountPercent,
        boolean clearanceEnabled,
        int clearanceStockAgeDays,
        int clearanceDiscountPercent) {

    /**
     * 关闭态哨兵：全部字段中性，{@link #activeAt(int)} 与 {@link #clearanceAppliesAt(int)} 恒 false。
     */
    private static final PricingPromoPolicy DISABLED =
            new PricingPromoPolicy(false, 0, 0, 0, false, 0, 0);

    public static PricingPromoPolicy disabled() {
        return DISABLED;
    }

    /** 只配时段折扣（清仓维度关闭）—— 保持既有调用方与判据不变。 */
    public static PricingPromoPolicy of(
            boolean enabled, int startHour, int endHour, int discountPercent) {
        return of(enabled, startHour, endHour, discountPercent, false, 0, 0);
    }

    /**
     * 由原始配置构造快照，两个维度**各自**归一化，任一维度不合法只关掉它自己：
     * <ul>
     *   <li>时段：总开关关闭 / 起止小时越界（不在 0-23）/ 比例 ≤0 或 ≥100 /
     *       起止小时相同（空窗口）；</li>
     *   <li>清仓：总开关关闭 / 天数阈值 ≤0 / 比例 ≤0 或 ≥100。</li>
     * </ul>
     * 两个维度都不生效时返回 {@link #DISABLED} 哨兵。
     */
    public static PricingPromoPolicy of(
            boolean enabled, int startHour, int endHour, int discountPercent,
            boolean clearanceEnabled, int clearanceStockAgeDays, int clearanceDiscountPercent) {
        boolean windowOn = enabled
                && isValidHour(startHour)
                && isValidHour(endHour)
                && discountPercent > 0
                && discountPercent < 100
                && startHour != endHour;
        boolean clearanceOn = clearanceEnabled
                && clearanceStockAgeDays > 0
                && clearanceDiscountPercent > 0
                && clearanceDiscountPercent < 100;
        if (!windowOn && !clearanceOn) {
            return DISABLED;
        }
        return new PricingPromoPolicy(
                windowOn,
                windowOn ? startHour : 0,
                windowOn ? endHour : 0,
                windowOn ? discountPercent : 0,
                clearanceOn,
                clearanceOn ? clearanceStockAgeDays : 0,
                clearanceOn ? clearanceDiscountPercent : 0);
    }

    /** 给定小时是否落在时段折扣窗口内。未开启或窗口非法时恒 false。 */
    public boolean activeAt(int hour) {
        if (!timeWindowEnabled || discountPercent <= 0 || startHour == endHour) {
            return false;
        }
        if (!isValidHour(hour)) {
            return false;
        }
        // start < end：普通窗口 [start, end)；start > end：跨零点窗口 [start, 24) ∪ [0, end)
        return startHour < endHour
                ? hour >= startHour && hour < endHour
                : hour >= startHour || hour < endHour;
    }

    /** 给定小时适用的时段折扣比例；不适用时为 0（即不打折）。 */
    public int discountPercentAt(int hour) {
        return activeAt(hour) ? discountPercent : 0;
    }

    /**
     * 给定「最早可售批次入库天数」是否触发清仓。
     *
     * <p>阈值语义是**闭区间下界**：{@code stockAgeDays >= clearanceStockAgeDays} 才生效。
     * 未开启、阈值 ≤0 或比例非法时恒 false。
     */
    public boolean clearanceAppliesAt(int stockAgeDays) {
        return clearanceEnabled
                && clearanceStockAgeDays > 0
                && clearanceDiscountPercent > 0
                && stockAgeDays >= clearanceStockAgeDays;
    }

    /** 给定入库天数适用的清仓折扣比例；不适用时为 0（即不打折）。 */
    public int clearanceDiscountAt(int stockAgeDays) {
        return clearanceAppliesAt(stockAgeDays) ? clearanceDiscountPercent : 0;
    }

    private static boolean isValidHour(int hour) {
        return hour >= 0 && hour <= 23;
    }
}
