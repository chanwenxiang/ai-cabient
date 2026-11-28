package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * 渠道退款闭环：只读查单/终态推进每分钟执行；重发与耗尽转人工每小时执行。
 *
 * <p>拆频目的：退款状态应尽快跟随渠道事实，但自动重发会动真钱，降为小时级减少风险。
 * 微信 ABNORMAL 不自动重发；支付宝同步接口无中间态，仍按原 out_request_no 重发。</p>
 */
@Component
public class RefundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryScheduler.class);
    static final String REFUND_QUERY = "refund-query";
    static final String REFUND_RETRY = "refund-retry";
    /** 痕迹创建超过该时长仍无终态 ⇒ 耗尽转人工。 */
    static final long EXHAUST_AFTER_MINUTES = 30;
    /** 痕迹静默期：HTTP 正常链路最长几十秒，2 分钟内不动（防与主链并发双发）。 */
    static final long QUIET_MINUTES = 2;

    private final PaymentOperationMapper paymentOperationMapper;
    private final OrderPaymentService orderPaymentService;
    private final WeChatRefundReconciler weChatRefundReconciler;
    private final ScheduledTaskService taskService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RefundResumeService refundResumeService;

    public RefundRetryScheduler(PaymentOperationMapper paymentOperationMapper,
                                OrderPaymentService orderPaymentService,
                                WeChatRefundReconciler weChatRefundReconciler,
                                ScheduledTaskService taskService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.orderPaymentService = orderPaymentService;
        this.weChatRefundReconciler = weChatRefundReconciler;
        this.taskService = taskService;
    }

    /** 只读查单与本地终态推进：每分钟，不重发渠道退款。 */
    @Scheduled(fixedRate = 60_000, initialDelay = 58_000)
    public void runQuery() {
        if (!taskService.tryBegin(REFUND_QUERY, 600)) {
            return;
        }
        run(REFUND_QUERY, () -> {
            int advanced = weChatRefundReconciler.advanceProcessingRefunds();
            int resumed = refundResumeService == null ? 0 : refundResumeService.resumePendingCompletedRefunds(20);
            return new Counts(0, advanced, resumed, 0);
        });
    }

    /** 自动重发与耗尽转人工：每小时，动钱动作降频。 */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 58_000)
    public void runRetry() {
        if (!taskService.tryBegin(REFUND_RETRY, 600)) {
            return;
        }
        run(REFUND_RETRY, () -> new Counts(closeStaleInitiated(), 0, 0, exhaustOverdue()));
    }

    private void run(String taskKey, Supplier<Counts> action) {
        long start = System.nanoTime();
        boolean failed = false;
        String summary = "无待闭环退款";
        try {
            Counts counts = action.get();
            if (counts.closed() <= 0 && counts.advanced() <= 0
                    && counts.resumed() <= 0 && counts.exhausted() <= 0) {
                return;
            }
            summary = "退款闭环 重发 " + counts.closed() + " 笔，查单推进 " + counts.advanced()
                    + " 笔，下游补跑 " + counts.resumed() + " 笔，耗尽转人工 " + counts.exhausted() + " 笔";
            log.info("refund task={} closed={} advanced={} resumed={} exhausted={}", taskKey,
                    counts.closed(), counts.advanced(), counts.resumed(), counts.exhausted());
        } catch (Exception e) {
            failed = true;
            taskService.finish(taskKey, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(taskKey, "SUCCESS", summary, start);
            }
        }
    }

    /** 扫超时 REFUND_INITIATED → 查单/重发闭环；单笔失败不阻断。 */
    private int closeStaleInitiated() {
        Instant quietBefore = Instant.now().minusSeconds(QUIET_MINUTES * 60);
        List<PaymentOperation> stale = paymentOperationMapper.selectList(
                Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getStatus, "REFUND_INITIATED")
                        .isNotNull(PaymentOperation::getGatewayTradeNo)
                        .lt(PaymentOperation::getUpdatedAt, quietBefore)
                        .orderByAsc(PaymentOperation::getUpdatedAt)
                        .last("LIMIT 20"));
        int closed = 0;
        for (PaymentOperation trace : stale) {
            try {
                if (orderPaymentService.retryFromInitiatedTrace(trace)) {
                    closed++;
                } else {
                    touch(trace);
                }
            } catch (Exception e) {
                log.warn("refund retry failed orderId={}: {}", trace.getOrderId(), e.getMessage());
            }
        }
        return closed;
    }

    /** 痕迹创建超 30 分钟仍无终态 ⇒ REFUND_FAILED + 告警转人工（按 created_at：touch 推后的 updated_at 不影响耗尽判定）。 */
    private int exhaustOverdue() {
        Instant deadline = Instant.now().minusSeconds(EXHAUST_AFTER_MINUTES * 60);
        List<PaymentOperation> overdue = paymentOperationMapper.selectList(
                Wrappers.<PaymentOperation>lambdaQuery()
                        .eq(PaymentOperation::getStatus, "REFUND_INITIATED")
                        .lt(PaymentOperation::getCreatedAt, deadline)
                        .orderByAsc(PaymentOperation::getUpdatedAt)
                        .last("LIMIT 20"));
        int exhausted = 0;
        for (PaymentOperation trace : overdue) {
            try {
                // BR4：先查渠道再置失败。SUCCESS ⇒ 走闭环而不是转人工；UNKNOWN ⇒ 本轮跳过，
                // 等下轮查单明确后再判，避免把「已退款成功但本地未收口」误标成失败。
                String channelStatus = orderPaymentService.queryInitiatedRefundStatus(trace);
                if ("UNKNOWN".equalsIgnoreCase(channelStatus)) {
                    log.warn("refund exhaust deferred: channel status unknown orderId={}", trace.getOrderId());
                    continue;
                }
                if ("SUCCESS".equalsIgnoreCase(channelStatus)
                        && orderPaymentService.retryFromInitiatedTrace(trace)) {
                    exhausted++;
                    continue;
                }
                orderPaymentService.markRefundFailed(trace, "超 " + EXHAUST_AFTER_MINUTES + " 分钟无终态");
                exhausted++;
            } catch (Exception e) {
                log.warn("refund exhaust failed orderId={}: {}", trace.getOrderId(), e.getMessage());
            }
        }
        return exhausted;
    }

    /** 处理过仍无终态的痕迹推后（updated_at 静默期重新计时；耗尽判定按 created_at 不受影响）。 */
    private void touch(PaymentOperation trace) {
        // F5：只对仍是 REFUND_INITIATED 的痕迹续命；已闭环/已失败的不许改回中间态。
        Instant now = Instant.now();
        if (paymentOperationMapper.touchIfInitiated(trace.getOperationId(), now) > 0) {
            trace.setUpdatedAt(now);
        }
    }

    private record Counts(int closed, int advanced, int resumed, int exhausted) {}
}
