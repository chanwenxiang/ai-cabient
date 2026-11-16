package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.RefundFlow;
import com.aicabinet.trade.mapper.RefundFlowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 退款流水记录（B3 专项，V485）。
 *
 * <p>唯一入口 {@link #record}：调用方在**推进订单 refunded_cents 的同一事务**里调用
 * （埋点同序，漏记只影响月账单退款分日显示，不影响资金真值——见 RefundFlow javadoc）。
 * 幂等：有 refundNo 走唯一索引（渠道重试防重）；无单号的本地退款按
 * (order_id, amount_cents, method) 最近 5 分钟内防重——重试窗口只有秒级，5 分钟足够，
 * 又不会把「同单两笔同额部分退」误合并。
 * 巡检兜底，审计流水宁多勿缺。</p>
 */
@Slf4j
@Service
public class RefundFlowService {

    /** 退款执行方式（refund_flow.refund_method）。 */
    public static final String METHOD_CHANNEL_REFUND = "CHANNEL_REFUND";
    public static final String METHOD_BALANCE = "BALANCE";
    public static final String METHOD_WECHAT_MANUAL = "WECHAT_MANUAL";
    public static final String METHOD_SETTLEMENT_PARTIAL = "SETTLEMENT_PARTIAL";
    public static final String METHOD_SETTLEMENT_WAIVE = "SETTLEMENT_WAIVE";
    public static final String METHOD_BACKFILL_AGGREGATE = "BACKFILL_AGGREGATE";

    private final RefundFlowMapper refundFlowMapper;

    public RefundFlowService(RefundFlowMapper refundFlowMapper) {
        this.refundFlowMapper = refundFlowMapper;
    }

    /**
     * 记一笔退款流水。
     *
     * @param order        已推进 refunded_cents 的订单（merchantId/payChannel 从它复制）
     * @param amountCents  本笔退款金额（分，>0）
     * @param method       RefundFlow.METHOD_* 执行方式
     * @param refundNo     渠道退款单号（可空；唯一索引幂等键）
     * @param refundedAt   退款发生时间（月账单分日键）
     * @param reason       退款原因（截断到 500）
     * @return 落库的流水 id；幂等命中/跳过时返回 null
     */
    public Long record(CabinetOrder order, int amountCents, String method,
                       String refundNo, Instant refundedAt, String reason) {
        if (order == null || order.getOrderId() == null || amountCents <= 0) {
            log.warn("refund flow skipped: invalid args order={} amount={}",
                    order == null ? null : order.getOrderId(), amountCents);
            return null;
        }
        if (refundNo != null && !refundNo.isBlank()) {
            Long exists = refundFlowMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<RefundFlow>lambdaQuery()
                            .eq(RefundFlow::getRefundNo, refundNo.trim()));
            if (exists != null && exists > 0) {
                log.info("refund flow idempotent skip refundNo={} order={}", refundNo, order.getOrderId());
                return null;
            }
        } else {
            // 无单号：5 分钟内同 (order, amount, method) 视为重放（重试窗口只有秒级；
            // 24h 会把「同单两笔同额部分退」误合并，导致月账单少报）。
            Long recent = refundFlowMapper.selectCount(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<RefundFlow>lambdaQuery()
                            .eq(RefundFlow::getOrderId, order.getOrderId())
                            .eq(RefundFlow::getAmountCents, amountCents)
                            .eq(RefundFlow::getRefundMethod, method)
                            .gt(RefundFlow::getRefundedAt, Instant.now().minus(java.time.Duration.ofMinutes(5))));
            if (recent != null && recent > 0) {
                log.info("refund flow recent-duplicate skip order={} amount={} method={}",
                        order.getOrderId(), amountCents, method);
                return null;
            }
        }
        RefundFlow row = new RefundFlow();
        row.setOrderId(order.getOrderId());
        // merchant_id 在 refund_flow 是 NOT NULL，而 cabinet_order.merchant_id 可空
        //（设备解绑后新单）——退款不能因为「这单没有商户」而把 finalize 事务打回。
        row.setMerchantId(order.getMerchantId() == null || order.getMerchantId().isBlank()
                ? "UNKNOWN" : order.getMerchantId().trim());
        row.setOrderPayChannel(order.getPayChannel() == null || order.getPayChannel().isBlank()
                ? "UNKNOWN" : order.getPayChannel().trim());
        row.setAmountCents(amountCents);
        row.setRefundMethod(method);
        row.setRefundNo(refundNo == null || refundNo.isBlank() ? null : refundNo.trim());
        row.setRefundedAt(refundedAt == null ? Instant.now() : refundedAt);
        row.setReason(truncate(reason, 500));
        // MA-271 修正：ON CONFLICT DO NOTHING —— 并发重投不会把调用方事务打成 aborted。
        int inserted = refundFlowMapper.insertIgnore(row);
        return inserted > 0 ? row.getFlowId() : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 8) + "...[截断]";
    }
}
