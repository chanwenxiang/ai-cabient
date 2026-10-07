package com.aicabinet.common.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V311：`WriteOffReasonCategory.infer` 的分类推断。
 *
 * <p>🔴 本类守护的核心语义是<b>「不猜」</b>：
 * 推断不出就返回 {@code null}，**绝不返回 {@code OTHER}**。
 * 因为分类错误会**找错责任方**（把破损报成过期 ⇒ 找供应商而不是物流），
 * 这比「没有分类」危险得多 —— 后者只是数据质量问题，前者是错误决策。
 */
class WriteOffReasonCategoryTest {

    @Test
    @DisplayName("精确匹配：现有白名单的 4 个值都能分类")
    void exactMatch_existingWhitelist() {
        assertEquals(WriteOffReasonCategory.EXPIRED, WriteOffReasonCategory.infer("EXPIRED"));
        assertEquals(WriteOffReasonCategory.DAMAGED, WriteOffReasonCategory.infer("DAMAGED"));
        assertEquals(WriteOffReasonCategory.LOST, WriteOffReasonCategory.infer("LOST"));
    }

    @Test
    @DisplayName("大小写与空白不敏感")
    void caseAndSpaceInsensitive() {
        assertEquals(WriteOffReasonCategory.EXPIRED, WriteOffReasonCategory.infer("expired"));
        assertEquals(WriteOffReasonCategory.EXPIRED, WriteOffReasonCategory.infer("  EXPIRED  "));
    }

    @Test
    @DisplayName("中文精确匹配")
    void chineseExactMatch() {
        assertEquals(WriteOffReasonCategory.EXPIRED, WriteOffReasonCategory.infer("过期"));
        assertEquals(WriteOffReasonCategory.EXPIRED, WriteOffReasonCategory.infer("临期"));
        assertEquals(WriteOffReasonCategory.DAMAGED, WriteOffReasonCategory.infer("破损"));
        assertEquals(WriteOffReasonCategory.DAMAGED, WriteOffReasonCategory.infer("损坏"));
    }

    @Test
    @DisplayName("🔴 「不过期」不能被包含匹配误判成「过期」")
    void negativePhrase_notMisjudgedAsExpired() {
        // 若把包含匹配放在精确匹配之前，「不过期」会命中 r.contains("过期")
        // ⇒ 报损「不过期」的商品被归为过期 ⇒ 找错责任方。
        // 这条用例钉住「精确匹配优先」的顺序。
        assertNull(WriteOffReasonCategory.infer("不过期"));
    }

    @Test
    @DisplayName("包含匹配：带说明文字时仍能分类")
    void containsMatch() {
        assertEquals(WriteOffReasonCategory.EXPIRED, WriteOffReasonCategory.infer("临期30天以上"));
        assertEquals(WriteOffReasonCategory.DAMAGED, WriteOffReasonCategory.infer("外力挤压破损"));
        assertEquals(WriteOffReasonCategory.LOST, WriteOffReasonCategory.infer("运输中丢失"));
        assertEquals(WriteOffReasonCategory.SHRINKAGE, WriteOffReasonCategory.infer("月末盘亏"));
    }

    @Test
    @DisplayName("🔴 推断不出返回 null 而非 OTHER（不猜）")
    void unknownReturnsNullNotOther() {
        // 「OTHER」是运营明确选的兜底值；「无法判断」不能等同于它
        assertNull(WriteOffReasonCategory.infer("OTHER"));
        assertNull(WriteOffReasonCategory.infer("其他"));
        assertNull(WriteOffReasonCategory.infer("随便写的原因"));
        assertNull(WriteOffReasonCategory.infer("Z1"));
    }

    @Test
    @DisplayName("空输入返回 null")
    void blankReturnsNull() {
        assertNull(WriteOffReasonCategory.infer(null));
        assertNull(WriteOffReasonCategory.infer(""));
        assertNull(WriteOffReasonCategory.infer("   "));
    }

    @Test
    @DisplayName("THEFT（现有白名单值）也能映射到 LOST")
    void theftMapsToLost() {
        // 现有白名单是 EXPIRED/DAMAGED/THEFT/OTHER，没有 LOST。
        // THEFT 与 LOST 语义相近（都是「少了」），归到同一类便于统计。
        assertEquals(WriteOffReasonCategory.LOST, WriteOffReasonCategory.infer("THEFT"));
    }
}