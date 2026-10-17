package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F-9（CB-057）：佣金基数改读**收款时冻结的毛额**，不再用会被回写的 {@code totalAmountCents}。
 *
 * <p>病灶：{@code total_amount_cents} 有三条就地回写路径（按行部分退摊薄、争议改单、结算改单），
 * 而旧口径 {@code total − refunded} 把退款**扣了两遍**——原 1000 退 700 的单，
 * total 已是 300、refunded 又记 700 ⇒ 净 GMV 算成 0（应为 300）。
 * 修前 1/2/3 号用例必红。
 */
class CommissionBaseCalcFrozenGrossTest {

    private CabinetOrder order(String status, int paidAmountCents, int totalAmountCents, int refundedCents) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-F9");
        order.setStatus(status);
        order.setPaidAmountCents(paidAmountCents);
        order.setTotalAmountCents(totalAmountCents);
        order.setRefundedCents(refundedCents);
        return order;
    }

    @Test
    void partialRefundDilutedTotal_usesFrozenGross() {
        // 原 1000，按行部分退 700：total 被摊薄成 300，refunded 记 700
        CabinetOrder order = order("PARTIAL_REFUNDED", 1000, 300, 700);

        assertEquals(1000, CommissionBaseCalc.grossCents(order), "毛额取冻结值，不是被摊薄的 total");
        assertEquals(300, CommissionBaseCalc.netGmv(List.of(order)),
                "退款只能扣一遍：修前用 total(300)−700 会算成 0");
    }

    @Test
    void disputeRepricedDown_usesFrozenGross() {
        // 争议改单降价 1000→800：改单回写 total，负差额又走 refundOrder 累加 refunded
        CabinetOrder order = order("PAID", 1000, 800, 200);

        assertEquals(800, CommissionBaseCalc.netGmv(List.of(order)),
                "改单降价后净额仍应收敛到实退后的 800，而非 total−refunded=600");
    }

    @Test
    void disputeRepricedUpThenRefunded_keepsAdjustedGross() {
        // 补扣涨价到 1300（CHARGE 1000 + ADJUST_CHARGE 300 已冻结），随后部分退 200
        CabinetOrder order = order("PARTIAL_REFUNDED", 1300, 1100, 200);

        assertEquals(1100, CommissionBaseCalc.netGmv(List.of(order)));
    }

    @Test
    void legacyOrderWithoutFrozenAmount_fallsBackToTotal() {
        // V384 之前、且流水里查不到收入行的历史单：冻结值 0 ⇒ 回落 total（Z 口径：0=没收过）
        CabinetOrder order = order("PAID", 0, 1000, 200);

        assertEquals(1000, CommissionBaseCalc.grossCents(order), "冻结值为 0 时回落 total，不能让历史单 GMV 归零");
        assertEquals(800, CommissionBaseCalc.netGmv(List.of(order)));
    }

    @Test
    void refundedOrder_isNotCounted() {
        CabinetOrder order = order("REFUNDED", 1000, 1000, 1000);

        assertEquals(0, CommissionBaseCalc.netGmv(List.of(order)), "全额退款单不进佣金基数（状态口径未变）");
    }
}
