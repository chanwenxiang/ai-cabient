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
 * 幂等：渠道退款按 refund_no 唯一；本地退款按 idem_key（订单+方式+本次退款后的累计已退额）
 * 唯一——同笔重放同键、同额第二笔累计额不同不会被合并（2026-11-16 R23 复核根治）。
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
        }
        // 真实幂等键（2026-11-16 R23 复核）：渠道退款 'NO:'+refundNo；本地退款
        // 'LOCAL:'+orderId+':'+method+':'+**本次退款后的累计已退额**。
        // 调用方在 record 之前先推进 refunded_cents（completeLocalRefund / 结算两处均如此），
        // 累计额单调递增 ⇒ 同一笔重放同键；同额的第二笔合法退款累计额不同 ⇒ 不会被合并。
        String normalizedNo = refundNo == null || refundNo.isBlank() ? null : refundNo.trim();
        String idemKey;
        if (normalizedNo != null) {
            idemKey = "NO:" + normalizedNo;
        } else {
            int cumulative = order.getRefundedCents();
            if (cumulative <= 0) {
                // 调用方漏推进累计额：退回「金额」兜底（仍比 5 分钟时间窗强，但会少区分同额两笔）。
                log.warn("refund flow local refund before refunded_cents advanced order={} amount={}",
                        order.getOrderId(), amountCents);
                cumulative = amountCents;
            }
            idemKey = "LOCAL:" + order.getOrderId() + ":" + method + ":" + cumulative;
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
        row.setRefundNo(normalizedNo);
        row.setIdemKey(idemKey);
        row.setRefundedAt(refundedAt == null ? Instant.now() : refundedAt);
        row.setReason(truncate(reason, 500));
        // ON CONFLICT DO NOTHING：覆盖 refund_no / idem_key 两张唯一索引，且不污染调用方事务。
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
