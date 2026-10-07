package com.aicabinet.common.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V312：盘点差异分类的<b>方向一致性</b>校验。
 *
 * <p>🔴 本类钉住的是**最容易被填反、且填反后极难发现**的地方：
 * 把盘盈分类（{@code MISENTRY_GAIN}）填到盘亏行上，
 * 统计会把「货多了」算成「货少了」——
 * 数字看起来正常（都是差值），只有逐行比对才能发现方向反了。
 *
 * <p>为什么必须**在代码里**判而不能靠人记：盘点是**高频重复操作**
 * （每个仓每次盘点都要填几十行），靠人保证不出错不现实。
 */
class StocktakeDiffReasonTest {

    @Test
    @DisplayName("盘亏分类配盘亏方向（diffQty < 0）⇒ 合法")
    void lossCategory_withNegativeDiff() {
        assertTrue(StocktakeDiffReason.matchesDirection(-5,
                StocktakeDiffReason.NORMAL_SHRINKAGE));
        assertTrue(StocktakeDiffReason.matchesDirection(-100,
                StocktakeDiffReason.SUSPECTED_THEFT));
        assertTrue(StocktakeDiffReason.matchesDirection(-1,
                StocktakeDiffReason.MISENTRY));
    }

    @Test
    @DisplayName("盘盈分类配盘盈方向（diffQty > 0）⇒ 合法")
    void gainCategory_withPositiveDiff() {
        assertTrue(StocktakeDiffReason.matchesDirection(3,
                StocktakeDiffReason.MISENTRY_GAIN));
        assertTrue(StocktakeDiffReason.matchesDirection(10,
                StocktakeDiffReason.SKU_MIXUP));
    }

    @Test
    @DisplayName("🔴 盘亏行填盘盈分类 ⇒ 不合法（方向反了）")
    void lossRow_withGainCategory_isRejected() {
        // 这正是「数字看起来正常、只有逐行比对才能发现」的那类错误
        assertFalse(StocktakeDiffReason.matchesDirection(-5,
                StocktakeDiffReason.MISENTRY_GAIN));
        assertFalse(StocktakeDiffReason.matchesDirection(-5,
                StocktakeDiffReason.SKU_MIXUP));
    }

    @Test
    @DisplayName("🔴 盘盈行填盘亏分类 ⇒ 不合法")
    void gainRow_withLossCategory_isRejected() {
        assertFalse(StocktakeDiffReason.matchesDirection(3,
                StocktakeDiffReason.NORMAL_SHRINKAGE));
        assertFalse(StocktakeDiffReason.matchesDirection(3,
                StocktakeDiffReason.SUSPECTED_THEFT));
    }

    @Test
    @DisplayName("OTHER 双向都合法（它是「说不清但确实是差异」）")
    void otherCategory_bothDirections() {
        assertTrue(StocktakeDiffReason.matchesDirection(-1, StocktakeDiffReason.OTHER));
        assertTrue(StocktakeDiffReason.matchesDirection(1, StocktakeDiffReason.OTHER));
    }

    @Test
    @DisplayName("未分类（null / 空白）永远合法 —— 不强制填")
    void unclassified_alwaysAllowed() {
        // 🔴 这条是「不强制填分类」的实现：强制会让运营为过校验而随便选一个，
        // 假数据比缺数据更坏（会让「某仓反复盘亏」这类异常彻底看不出来）。
        assertTrue(StocktakeDiffReason.matchesDirection(-5, null));
        assertTrue(StocktakeDiffReason.matchesDirection(-5, ""));
        assertTrue(StocktakeDiffReason.matchesDirection(-5, "   "));
    }

    @Test
    @DisplayName("🔴 无差异行（diffQty = 0）不该有分类 ⇒ 非法")
    void noDiff_withCategory_isRejected() {
        // 有差异分类但无差异，通常是流程写错了（比如先填分类再改实盘数）
        assertFalse(StocktakeDiffReason.matchesDirection(0,
                StocktakeDiffReason.NORMAL_SHRINKAGE));
        assertFalse(StocktakeDiffReason.matchesDirection(0, StocktakeDiffReason.OTHER));
    }

    @Test
    @DisplayName("isLossCategory 分类归属正确")
    void lossCategoryMembership() {
        assertTrue(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.NORMAL_SHRINKAGE));
        assertTrue(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.MISENTRY));
        assertTrue(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.HANDLING_DAMAGE));
        assertTrue(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.SUSPECTED_THEFT));

        assertFalse(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.MISENTRY_GAIN));
        assertFalse(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.UNRECORDED_RETURN));
        assertFalse(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.SKU_MIXUP));
        // 🔴 OTHER 不「属于」盘亏类，但这**不代表**它只能填盘盈行 ——
//   isLossCategory 是归属判断（false = 不属于任一方向）；
        //   matchesDirection 是校验规则，OTHER 在那里被显式放行双向。
        //   两者是不同的问题，答案可以相反。
        assertFalse(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.OTHER));
        assertTrue(StocktakeDiffReason.matchesDirection(-1, StocktakeDiffReason.OTHER));
        assertTrue(StocktakeDiffReason.matchesDirection(1, StocktakeDiffReason.OTHER));
    }

    @Test
    @DisplayName("🔴 MISENTRY 与 MISENTRY_GAIN 是两个不同分类（不能被当作同一个）")
    void misentryVariantsAreDistinct() {
        // 「错记」在盘亏与盘盈两个方向都可能出现，语义不同：
        //   盘亏+错记 = 账面记多了
        //   盘盈+错记 = 账面记少了
        // 把它们混为一谈会让「错记」这个分类失去追责价值。
        assertFalse(StocktakeDiffReason.MISENTRY.equals(StocktakeDiffReason.MISENTRY_GAIN));
        assertFalse(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.MISENTRY_GAIN));
        assertTrue(StocktakeDiffReason.isLossCategory(StocktakeDiffReason.MISENTRY));
    }
}