package com.aicabinet.common.constants;

/**
 * V312：盘点差异原因分类（受控枚举）。
 *
 * <p>🔴 <b>与 {@code WriteOffReasonCategory} 是两条链路，勿混用</b>：
 * 那套记「<b>主动核销</b>的原因」（已知损耗，报损时填）；
 * 这套记「<b>账实不符</b>的原因」（盘点时发现对不上，要查为什么）。
 * 语义不同 —— 混用会让「谁该为损耗负责」的统计彻底失真。
 *
 * <p><b>为什么必须有它</b>：盘亏是要<b>追责</b>的。没有分类就只知道
 * 「这个仓这个月亏了 300 件」，无法回答「是不是有人在偷」。
 * 有了分类才能定位异常模式（某仓反复盘亏 ⇒ 疑似管理问题）。
 *
 * <p>⚠️ <b>不加 DB CHECK 约束</b>：与 {@code WriteOffReasonCategory} 同理由 ——
 * 应用侧契约先行，待存量数据归一化后再补约束。
 *
 * <p>📌 <b>null 的语义</b>：{@code null} = <b>未分类</b>（要治理的问题），
 * <b>不等于</b> {@link #OTHER}。
 */
public final class StocktakeDiffReason {

    // ===== 盘亏类（账面 > 实盘，货少了）=====

    /** 正常损耗：过期/破损/挥发。属可预期损失。 */
    public static final String NORMAL_SHRINKAGE = "NORMAL_SHRINKAGE";
    /** 错记：入库时记错账、或上期盘点不准。 */
    public static final String MISENTRY = "MISENTRY";
    /** 搬运破损：装卸/移库时磕碰。 */
    public static final String HANDLING_DAMAGE = "HANDLING_DAMAGE";
    /** 疑似丢失/失窃：需追责。 */
    public static final String SUSPECTED_THEFT = "SUSPECTED_THEFT";

    // ===== 盘盈类（账面 < 实盘，货多了）=====

    /** 错记：账面少记但实物在。 */
    public static final String MISENTRY_GAIN = "MISENTRY_GAIN";
    /** 退货入库未登记。 */
    public static final String UNRECORDED_RETURN = "UNRECORDED_RETURN";
    /** 串货：别的 SKU 放错了位置。 */
    public static final String SKU_MIXUP = "SKU_MIXUP";

    /** 运营明确选「其他」。 */
    public static final String OTHER = "OTHER";

    private StocktakeDiffReason() {
    }

    /**
     * 该分类是否属于「盘亏」方向。
     *
     * <p>🔴 <b>这个判断必须在代码里做，不能靠人记</b>：
     * 把 {@link #MISENTRY_GAIN}（盘盈）填到盘亏行上，会让统计把
     * 「货多了」算成「货少了」，方向直接反掉。
     */
    public static boolean isLossCategory(String reason) {
        return NORMAL_SHRINKAGE.equals(reason)
                || MISENTRY.equals(reason)
                || HANDLING_DAMAGE.equals(reason)
                || SUSPECTED_THEFT.equals(reason);
    }

    /**
     * 校验分类与差异方向是否匹配。
     *
     * @param diffQty 实盘 − 账面（&lt;0 盘亏/ &gt;0 盘盈 / 0 无差异）
     * @param reason  要写入的分类
     * @return true 表示合法
     */
    public static boolean matchesDirection(int diffQty, String reason) {
        if (reason == null || reason.isBlank()) {
            return true; // 未分类永远合法（不强制填）
        }
        if (diffQty == 0) {
            // 无差异的行不该有分类 —— 有分类但无差异，通常是流程写错了
            return false;
        }
        //🔴 {@link #OTHER} 必须**双向都合法**：
        //   它表达的是「确实是差异，但原因说不清」—— 而原因说不清与方向无关。
        //   若按 isLossCategory(OTHER)=false 处理，盘亏行填 OTHER 会被误拒，
        //   逼着运营硬选一个分类 ⇒ 假数据（比未分类更坏）。
        if (OTHER.equals(reason)) {
            return true;
        }
        boolean loss = isLossCategory(reason);
        return loss == (diffQty < 0);
    }
}