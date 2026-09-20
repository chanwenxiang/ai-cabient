package com.aicabinet.trade.service;

/**
 * 定价促销策略快照（F1 动态定价 · 时段折扣）。
 *
 * <p><b>为什么要快照而不是每算一次价就查一次配置</b>：{@code resolveUnitPriceCents} 是在
 * 「商品目录」「结算明细」这类**循环里**逐个 SKU 调用的，而系统配置是直读 DB、
 * 无缓存（刻意如此，保证运营改完即时生效）。若在方法内逐键读取，一次目录查询就会
 * 变成 {@code SKU 数 × 4} 次查库。故由 {@link MerchantSkuPricingService#loadPromoPolicy()}
 * 读一次快照，调用方在循环外取、循环内复用。
 *
 * <p><b>归一化即 fail-closed</b>：任何越界/自相矛盾的配置都收敛成「不生效」，
 * 而不是猜一个近似值。写错配置的后果是「折扣没生效（原价成交）」，
 * 绝不会是「算出 0 元」或「倒贴钱」。
 */
public record PricingPromoPolicy(
        boolean timeWindowEnabled,
        int startHour,
        int endHour,
        int discountPercent) {

    /** 关闭态哨兵：全部字段中性，{@link #activeAt(int)} 恒 false。 */
    private static final PricingPromoPolicy DISABLED = new PricingPromoPolicy(false, 0, 0, 0);

    public static PricingPromoPolicy disabled() {
        return DISABLED;
    }

    /**
     * 由原始配置构造快照。以下任一情况直接退回「不生效」：
     * <ul>
     *   <li>总开关关闭；</li>
     *   <li>起止小时越界（不在 0-23）；</li>
     *   <li>折扣比例 ≤0 或 ≥100（100% 折扣不是合法折扣，宁可原价也不白送）；</li>
     *   <li>起止小时相同 —— 空窗口，视为「没配」。</li>
     * </ul>
     */
    public static PricingPromoPolicy of(
            boolean enabled, int startHour, int endHour, int discountPercent) {
        if (!enabled) {
            return DISABLED;
        }
        if (!isValidHour(startHour) || !isValidHour(endHour)) {
            return DISABLED;
        }
        if (discountPercent <= 0 || discountPercent >= 100) {
            return DISABLED;
        }
        if (startHour == endHour) {
            return DISABLED;
        }
        return new PricingPromoPolicy(true, startHour, endHour, discountPercent);
    }

    /** 给定小时是否落在折扣窗口内。未开启或窗口非法时恒 false。 */
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

    /** 给定小时适用的折扣比例；不适用时为 0（即不打折）。 */
    public int discountPercentAt(int hour) {
        return activeAt(hour) ? discountPercent : 0;
    }

    private static boolean isValidHour(int hour) {
        return hour >= 0 && hour <= 23;
    }
}
