package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CouponScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V319 券可用范围判定测试。
 *
 * <p>🔴 <b>刻意调生产类 {@link CouponScopeValidator} / {@link CouponScopeType}本身</b>，
 * 不在本测试里抄一份判定逻辑 —— 抄一份的测试永远绿，改实现不会变红（假测试）。
 */
class CouponScopeValidatorTest {

    // ── ALL ────────────────────────────────────────────

    @Test
    @DisplayName("ALL 在任何柜机都可用")
    void all_allowsAnyDevice() {
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.ALL, null, null, "DEV-1", "MCH-A"));
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.ALL, null, "MCH-B", "DEV-1", "MCH-A"));
    }

    @Test
    @DisplayName("🔴 null/空白 scopeType 按 ALL 处理（存量券都是 null，不能因此全被拒）")
    void nullScopeType_isTreatedAsAll() {
        assertTrue(CouponScopeValidator.allowsDevice(null, null, null, "DEV-1", "MCH-A"));
        assertTrue(CouponScopeValidator.allowsDevice("  ", null, null, "DEV-1", "MCH-A"));
    }

    @Test
    @DisplayName("未知类型按 ALL 放行，而不是拒（拒绝会让存量券集体失效）")
    void unknownType_isTreatedAsAll() {
        assertTrue(CouponScopeValidator.allowsDevice("BOGUS", null, null, "DEV-1", "MCH-A"));
    }

    // ── DEVICE ─────────────────────────────────────────

    @Test
    @DisplayName("DEVICE：柜机在集合内 ⇒ 可用")
    void device_inSet_isAllowed() {
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.DEVICE, new String[]{"DEV-1", "DEV-2"}, null, "DEV-1", "MCH-A"));
    }

    @Test
    @DisplayName("DEVICE：柜机不在集合内 ⇒ 不可用（这才是范围的意义）")
    void device_notInSet_isRejected() {
        assertFalse(CouponScopeValidator.allowsDevice(
                CouponScopeType.DEVICE, new String[]{"DEV-1"}, null, "DEV-9", "MCH-A"));
    }

    @Test
    @DisplayName("DEVICE：柜机 ID 大小写/空格不敏感（DB 里大小写不统一）")
    void device_isCaseAndSpaceInsensitive() {
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.DEVICE, new String[]{"dev-1"}, null, " DEV-1 ", "MCH-A"));
    }

    @Test
    @DisplayName("🔴 DEVICE 但集合为空 ⇒ 不限制（退化为 ALL，而不是哪儿都不能用）")
    void device_emptySet_degradesToAll() {
        // 「建了券但还没选柜机」若判成「哪儿都不能用」，
        // 失败原因在界面上完全看不出来 ⇒ 这类问题极难排查。
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.DEVICE, new String[]{}, null, "DEV-9", "MCH-A"));
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.DEVICE, null, null, "DEV-9", "MCH-A"));
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.DEVICE, new String[]{"  ", null}, null, "DEV-9", "MCH-A"));
    }

    // ── MERCHANT ───────────────────────────────────────

    @Test
    @DisplayName("MERCHANT：柜机属于该商户 ⇒ 可用")
    void merchant_sameMerchant_isAllowed() {
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.MERCHANT, null, "MCH-A", "DEV-1", "MCH-A"));
    }

    @Test
    @DisplayName("MERCHANT：柜机属于别的商户 ⇒ 不可用")
    void merchant_otherMerchant_isRejected() {
        assertFalse(CouponScopeValidator.allowsDevice(
                CouponScopeType.MERCHANT, null, "MCH-A", "DEV-1", "MCH-B"));
    }

    @Test
    @DisplayName("MERCHANT：声明了范围但没填商户 ⇒ 不限制（不阻断，配置治理靠 validate）")
    void merchant_blankMerchant_doesNotBlock() {
        // 拒绝会让这张券彻底不可用且无提示；放行与「未配置范围」语义一致。
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.MERCHANT, null, null, "DEV-1", "MCH-A"));
    }

    @Test
    @DisplayName("MERCHANT：查不到柜机商户 ⇒ 放行（不因元数据缺失阻断交易）")
    void merchant_unknownDeviceMerchant_doesNotBlock() {
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.MERCHANT, null, "MCH-A", "DEV-1", null));
        assertTrue(CouponScopeValidator.allowsDevice(
                CouponScopeType.MERCHANT, null, "MCH-A", null, null));
    }

    // ── validate：写入时必须严格 ───────────────────────

    @Test
    @DisplayName("validate：MERCHANT 未选商户 ⇒ 报错（写入严格，判定宽松）")
    void validate_merchantWithoutId_isRejected() {
        assertEquals("范围类型选了「指定商户」，但没有选择商户",
                CouponScopeValidator.validate(CouponScopeType.MERCHANT, null, null));
    }

    @Test
    @DisplayName("validate：非法类型 ⇒ 报错")
    void validate_illegalType_isRejected() {
        assertEquals("可用范围类型非法", CouponScopeValidator.validate("BOGUS", null, null));
    }

    @Test
    @DisplayName("validate：柜机 ID 超长 ⇒ 报错")
    void validate_deviceIdTooLong_isRejected() {
        String tooLong = "D".repeat(65);
        assertEquals("柜机 ID 长度超限：" + tooLong,
                CouponScopeValidator.validate(CouponScopeType.DEVICE, new String[]{tooLong}, null));
    }

    @Test
    @DisplayName("validate：DEVICE 空集合合法（过渡态）")
    void validate_deviceEmptySet_isAllowed() {
        assertNull(CouponScopeValidator.validate(CouponScopeType.DEVICE, new String[]{}, null));
    }

    @Test
    @DisplayName("validate：合法组合 ⇒ null")
    void validate_validCombinations_pass() {
        assertNull(CouponScopeValidator.validate(CouponScopeType.ALL, null, null));
        assertNull(CouponScopeValidator.validate(CouponScopeType.DEVICE, new String[]{"DEV-1"}, null));
        assertNull(CouponScopeValidator.validate(CouponScopeType.MERCHANT, null, "MCH-A"));
    }

    // ── CouponScopeType ────────────────────────────────

    @Test
    @DisplayName("normalize：空白 ⇒ ALL（不返回 null，避免下游两种解读）")
    void normalize_blankBecomesAll() {
        assertEquals(CouponScopeType.ALL, CouponScopeType.normalize(null));
        assertEquals(CouponScopeType.ALL, CouponScopeType.normalize(""));
        assertEquals(CouponScopeType.ALL, CouponScopeType.normalize("  "));
    }

    @Test
    @DisplayName("normalize：小写/带空格 ⇒ 归一为大写合法值")
    void normalize_normalizes() {
        assertEquals(CouponScopeType.DEVICE, CouponScopeType.normalize(" device "));
        assertEquals(CouponScopeType.MERCHANT, CouponScopeType.normalize("merchant"));
    }

    @Test
    @DisplayName("normalize：非法值抛异常（不是静默回落 —— 静默会让人以为配成功了）")
    void normalize_illegalThrows() {
        assertThrows(IllegalArgumentException.class, () -> CouponScopeType.normalize("BOGUS"));
    }

    @Test
    @DisplayName("isValid：空白算合法（=未填），其它按集合判")
    void isValid_semantics() {
        assertTrue(CouponScopeType.isValid(null));
        assertTrue(CouponScopeType.isValid("  "));
        assertTrue(CouponScopeType.isValid("device"));
        assertFalse(CouponScopeType.isValid("nope"));
    }
}
