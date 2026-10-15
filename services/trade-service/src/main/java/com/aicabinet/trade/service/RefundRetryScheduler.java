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

/**
 * B1/G1（2026-10-15）：渠道退款重试闭环（分钟级）。
 *
 * <p>背景：渠道退款提交失败/进程中断此前本地零痕迹（「钱已退、账未记」只能靠 T+1 对账
 * 金额级兜底）。OrderPaymentService 现已在 HTTP 前落 REFUND_INITIATED 痕迹（H41 同构），
 * 本任务负责闭环：
 * <ol>
 *   <li><b>REFUND_INITIATED 超时</b>（updated_at &lt; now-2min）：查单优先（微信 queryRefund，
 *       渠道可能已退成功只是本地未达），确认无结果才同号重发（out_refund_no 渠道侧幂等）；
 *       距痕迹创建 &gt;30min 仍无终态 ⇒ 置 REFUND_FAILED + HIGH 告警转人工（不自动冲账）。</li>
 *   <li><b>PROCESSING 查单推进</b>：复用 {@link WeChatRefundReconciler#advanceProcessingRefunds()}
 *       （原每日对账附属、LIMIT 50，提升到分钟级；对账里的调用幂等保留为第二道）。</li>
 * </ol>
 * 微信 ABNORMAL 不自动重发（渠道侧需人工，臆断重发有双退风险）；支付宝同步接口无中间态，
 * 直接按同一 out_request_no 重发。余额退款卡单（balance_refund_request PROCESSING 超时）
 * 留待后续批次（H63 复审续退已部分覆盖）。
 */
@Component
public class RefundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryScheduler.class);
    static final String REFUND_RETRY = "refund-retry";
    /** 痕迹创建超过该时长仍无终态 ⇒ 耗尽转人工。 */
    static final long EXHAUST_AFTER_MINUTES = 30;
    /** 痕迹静默期：HTTP 正常链路最长几十秒，2 分钟内不动（防与主链并发双发）。 */
    static final long QUIET_MINUTES = 2;

    private final PaymentOperationMapper paymentOperationMapper;
    private final OrderPaymentService orderPaymentService;
    private final WeChatRefundReconciler weChatRefundReconciler;
    private final ScheduledTaskService taskService;

    public RefundRetryScheduler(PaymentOperationMapper paymentOperationMapper,
                                OrderPaymentService orderPaymentService,
                                WeChatRefundReconciler weChatRefundReconciler,
                                ScheduledTaskService taskService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.orderPaymentService = orderPaymentService;
        this.weChatRefundReconciler = weChatRefundReconciler;
        this.taskService = taskService;
    }

    @Scheduled(fixedRate = 60_000)
    public void run() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(REFUND_RETRY, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "无待闭环退款";
        try {
            int closed = closeStaleInitiated();
            int advanced = weChatRefundReconciler.advanceProcessingRefunds();
            int exhausted = exhaustOverdue();
            if (closed <= 0 && advanced <= 0 && exhausted <= 0) {
                return;
            }
            summary = "退款重试闭环 " + closed + " 笔，查单推进 " + advanced + " 笔，耗尽转人工 " + exhausted + " 笔";
            log.info("refund retry closed={} advanced={} exhausted={}", closed, advanced, exhausted);
        } catch (Exception e) {
            failed = true;
            taskService.finish(REFUND_RETRY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(REFUND_RETRY, "SUCCESS", summary, start);
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
        trace.setUpdatedAt(Instant.now());
        paymentOperationMapper.updateById(trace);
    }
}
