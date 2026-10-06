package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WithdrawFeeCalculatorTest {

    @Test
    void compute_flatPlusBps() {
        // 10000 * 50bps = 50, + flat 30 = 80
        assertEquals(80L, WithdrawFeeCalculator.computeFeeCents(10_000L, 30L, 50L));
        assertEquals(0L, WithdrawFeeCalculator.computeFeeCents(10_000L, 0L, 0L));
    }

    @Test
    void compute_rejectsWhenFeeNotLessThanAmount() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> WithdrawFeeCalculator.computeFeeCents(100L, 100L, 0L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void netPayout_subtractsFee() {
        assertEquals(9900L, WithdrawFeeCalculator.netPayoutCents(10_000L, 100L));
        assertEquals(10_000L, WithdrawFeeCalculator.netPayoutCents(10_000L, null));
    }

    // ============ V307：单笔封顶（对齐支付宝封顶 ¥25 / 拉卡拉封顶 ¥50）============

    /** 封顶生效：大额提现的手续费被卡在封顶值，不再按比例无限增长。 */
    @Test
    void computeFee_capLimitsLargeAmountFee() {
        // 0.5% 提现 ¥100000（1000 万分）= 50000 分（¥500）⇒ 应被 ¥50（5000 分）封顶
        assertEquals(5000L, WithdrawFeeCalculator.computeFeeCents(10_000_000L, 0L, 50L, 5000L));
        // 🔴 验算订正：¥10000（100 万分）× 0.5% = 5000 分 = **恰好等于封顶** ⇒ 结果 5000 而非 500。
        //    想验证「未触及封顶」要用更小的金额：¥1000（10 万分）× 0.5% = 500 分 < 5000
        assertEquals(5000L, WithdrawFeeCalculator.computeFeeCents(1_000_000L, 0L, 50L, 5000L));
        assertEquals(500L, WithdrawFeeCalculator.computeFeeCents(100_000L, 0L, 50L, 5000L),
                "未触及封顶时应按比例原值计算");
    }

    /** 封顶 = 0 表示不封顶（保持接入前行为，零行为变化）。 */
    @Test
    void computeFee_zeroCapMeansUncapped() {
        assertEquals(50_000L, WithdrawFeeCalculator.computeFeeCents(10_000_000L, 0L, 50L, 0L));
        assertEquals(50_000L, WithdrawFeeCalculator.computeFeeCents(10_000_000L, 0L, 50L, -1L),
                "负数封顶应等同于不封顶");
    }

    /** 封顶同时作用于固定手续费与比例手续费之和（不是各自封顶再相加）。 */
    @Test
    void computeFee_capAppliesToTotalNotEachComponent() {
        // flat=8000 + 0.5%*1000000=500 ⇒ 合计 8500 > 封顶 5000 ⇒ 取 5000
        assertEquals(5000L, WithdrawFeeCalculator.computeFeeCents(1_000_000L, 8000L, 50L, 5000L));
        // 🔴 验算订正：flat=8000 > 提现额 1000 ⇒ 未封顶时就已触发「手续费≥金额」⇒ 400，
        //    封顶 5000 救不回来（封顶只会更小）。改用 flat=600（<金额）来验证「固定额也被封顶」。
        assertEquals(5000L, WithdrawFeeCalculator.computeFeeCents(100_000L, 6000L, 0L, 5000L),
                "固定手续费 6000 > 封顶 5000 ⇒ 取封顶值");
    }

    /**
     * 封顶让「手续费 ≥ 提现金额」变得可达 —— 此时仍须 400，否则会算出净额 ≤ 0 的打款。
     *
     * <p>🔴 验算订正：原用例用 {@code 1000 分 × 50bps}，但 50bps = 0.5%，
     * 1000 × 50/10000 = <b>5 分</b>（不是 50），远小于 1000 ⇒ 合法。
     * 改用固定手续费 1500 分（&gt; 金额 1000）来真正触发「手续费 ≥ 金额」。
     */
    @Test
    void computeFee_rejectsWhenCappedFeeNotLessThanAmount() {
        // 提现 ¥10（1000 分），固定手续费 ¥15（1500 分）> 金额 ⇒ 拒绝
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> WithdrawFeeCalculator.computeFeeCents(1_000L, 1_500L, 0L, 0L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    /** 溢出保护：bps 异常大时不能算出负数手续费（否则变成「倒贴」）。 */
    @Test
    void computeFee_doesNotOverflowToNegative() {
        // bps=1_000_000（=100 倍）配巨额提现 ⇒ 乘法溢出为负 ⇒ 应被识别并按「≥金额」拒绝，
        // 而不是返回一个负数手续费（那会让 netPayout 变成「倒贴给商户」）。
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> WithdrawFeeCalculator.computeFeeCents(Long.MAX_VALUE / 100, 0L, 1_000_000L, 0L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    /** 金额非法时仍优先报「金额必须大于 0」，不被封顶逻辑掩盖。 */
    @Test
    void computeFee_rejectsNonPositiveAmountEvenWithCap() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> WithdrawFeeCalculator.computeFeeCents(0L, 0L, 50L, 5000L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    /**
     * 封顶配置得比提现金额还大时，<b>按比例算出的手续费才是判据</b>。
     *
     * <p>🔴 本用例初版断言「应拒绝」，属<b>断言错误</b>：封顶只能<b>压低</b>手续费，
     * 不会把一个本已低于金额的手续费抬到 ≥ 金额。
     * 提现 ¥10（1000 分）× 50bps（0.5%）= 5 分，远小于 1000 ⇒ 合法放行。
     * 保留此用例是为了钉住「封顶不改变合法判定」这一边界（防止有人误改成「封顶也参与拒绝判定」）。
     */
    @Test
    void computeFee_capLargerThanAmount_doesNotChangeLegalResult() {
        assertEquals(5L, WithdrawFeeCalculator.computeFeeCents(1_000L, 0L, 50L, 20_000L));
    }

    /** 封顶恰好把手续费压到小于金额时<b>应放行</b>（封顶的正当用途）。 */
    @Test
    void computeFee_allowsWhenCapBringsFeeBelowAmount() {
        // 提现 ¥10（1000 分），flat=¥15（1500 分）本应被拒；封顶 ¥5（500 分）后合法
        assertEquals(500L, WithdrawFeeCalculator.computeFeeCents(1_000L, 1500L, 0L, 500L));
    }
}
