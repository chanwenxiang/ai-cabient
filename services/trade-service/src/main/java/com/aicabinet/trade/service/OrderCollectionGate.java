package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;

import java.util.Locale;
import java.util.Set;

/**
 * 「这单的钱到底进来了没有」的**唯一判据**（R20-2，2026-11-13）。
 *
 * <p>订单在 {@code SettlementOrderFinalizeService.buildOrder} 建出来时就是 PAID，落到
 * {@code PENDING} 只有两条路，两条都**还没有从消费者收到钱**：
 * <ul>
 *   <li>微信支付分免密腿——{@code OrderPaymentService.tryCompleteWeChatPayScore} 只「完结订单」，
 *       扣款是微信**异步**的，实收与否要等 {@code PayScoreOrderNotifyService} 回调；</li>
 *   <li>扣款失败转待支付——{@code markPendingAndDropCoupon} / {@code UnpaidOrderService} 补缴链路。</li>
 * </ul>
 * {@code CANCELLED} 是待支付单被取消（F1-C 已禁止净额&gt;0 的单被自动取消）。
 *
 * <p>刻意做成「排除未实收」而不是「只认 PAID」：改单重算、部分退款等路径拿到的是
 * {@code PARTIAL_REFUNDED / REFUNDED} 这类**已经实收过**的状态，若写成 {@code == PAID}
 * 会把它们静默排除在入账/发放之外——那是把一个资损换成另一个方向、且更难发现的资损。
 *
 * <p>{@code status} 为空（列 NOT NULL，只有单测里直接 new 的订单会这样）按**已实收**处理：
 * 判据的失败方向必须是「照常入账」，不能是「静默不入账」。
 *
 * <p>谁用这一判据：{@link RevenueSplitService}（分账行入不入商户钱包）与
 * {@link SettlementOrderFinalizeService}（会员消费统计/返积分与 order_paid 通知）。
 * 两处必须同源——分账挪到实收、积分还留在结算，就会出现「商户没入账、用户已拿分」的半修状态。
 */
public final class OrderCollectionGate {

    private static final Set<String> UNCOLLECTED_STATUSES = Set.of("PENDING", "CANCELLED");

    private OrderCollectionGate() {
    }

    /** 该订单是否已实收（见类注释）。 */
    public static boolean collected(CabinetOrder order) {
        String status = order == null || order.getStatus() == null
                ? ""
                : order.getStatus().trim().toUpperCase(Locale.ROOT);
        return !UNCOLLECTED_STATUSES.contains(status);
    }
}
