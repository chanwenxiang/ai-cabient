package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;

import java.util.List;
import java.util.Set;

/**
 * 佣金基数统一口径（审计 T2/P0② 修复，2026-10-15；F-9 基数改冻结毛额，2026-10-17 CB-057）：
 * <ul>
 *   <li><b>毛额</b>：取收款时点冻结的 {@code paidAmountCents}，<b>不是</b> {@code totalAmountCents}
 *       ——后者会被按行部分退摊薄、被争议改单回写，与 {@code refundedCents} 叠加就是
 *       「同一笔退款扣两遍」（原 1000 退 700 会算成 0）；</li>
 *   <li><b>净额</b>：GMV = 冻结毛额 − refunded（部分退款按净额计，不再整单排除）；</li>
 *   <li><b>含 PARTIAL_REFUNDED</b>：部分退款单有净收入，发佣要计、冲正重算按净额差额；
 *       全额退款（REFUNDED）与未支付状态不计。</li>
 * </ul>
 * 发佣（{@link LineCommissionJob}）与冲正（{@link CommissionClawbackService}）必须同源，
 * 防止「发佣按全额、冲回按净额」产生系统性差额（老系统差异卡 §6.1 实锤口径）。
 */
public final class CommissionBaseCalc {

    /** 计入佣金基数的状态（净额 &gt; 0 才产生 GMV；状态可计即计单数）。 */
    public static final Set<String> COUNTABLE_STATUSES = Set.of("PAID", "COMPLETED", "PARTIAL_REFUNDED");

    private CommissionBaseCalc() {
    }

    /** 订单是否计入佣金基数（状态口径）。 */
    public static boolean countable(CabinetOrder order) {
        return order.getStatus() != null
                && COUNTABLE_STATUSES.contains(order.getStatus().toUpperCase());
    }

    /**
     * 单笔毛额（成交额口径）：优先取收款时冻结的 {@code paidAmountCents}；
     * 冻结值为 0（欠款单、V384 之前无流水的历史单）时回落 {@code totalAmountCents}。
     */
    public static long grossCents(CabinetOrder order) {
        int frozen = order.getPaidAmountCents();
        return frozen > 0 ? frozen : Math.max(0, order.getTotalAmountCents());
    }

    /** 单笔净额：冻结毛额 − refunded（refundedCents 兜底非负）。 */
    public static long netCents(CabinetOrder order) {
        return Math.max(0L, grossCents(order) - Math.max(0, order.getRefundedCents()));
    }

    /** 一批订单的净 GMV（仅计 countable 行）。 */
    public static long netGmv(List<CabinetOrder> orders) {
        return orders.stream().filter(CommissionBaseCalc::countable)
                .mapToLong(CommissionBaseCalc::netCents).sum();
    }

    /** 一批订单的可计单数。 */
    public static int countableCount(List<CabinetOrder> orders) {
        return (int) orders.stream().filter(CommissionBaseCalc::countable).count();
    }
}
