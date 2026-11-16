package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * B1-B2：CHARGE_PENDING（渠道扣款结果未知）自动对账。
 *
 * <p>2 分钟静默后按痕迹里的 attempted_channel 查单：SUCCESS 补收口；
 * FAILED/CLOSED 终结痕迹允许重试；UNKNOWN 下一轮。15 分钟仍未知发一次 HIGH 告警，
 * 60 分钟由既有 alertStaleChargePendingOps 升级。旧痕迹无元数据 ⇒ 不自动收口，只告警。</p>
 */
@Component
public class ChargePendingReconciler {

    private static final Logger log = LoggerFactory.getLogger(ChargePendingReconciler.class);
    static final String TASK_KEY = "charge-pending-reconcile";
    static final long QUIET_SECONDS = 120;
    static final long FIRST_ALERT_MINUTES = 15;
    static final long FIRST_ALERT_WINDOW_MINUTES = 2;

    private final PaymentOperationMapper paymentOperationMapper;
    private final OrderPaymentService orderPaymentService;
    private final UnpaidOrderService unpaidOrderService;
    private final OpsExceptionService opsExceptionService;
    private final ScheduledTaskService taskService;

    public ChargePendingReconciler(PaymentOperationMapper paymentOperationMapper,
                                   OrderPaymentService orderPaymentService,
                                   UnpaidOrderService unpaidOrderService,
                                   OpsExceptionService opsExceptionService,
                                   ScheduledTaskService taskService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.orderPaymentService = orderPaymentService;
        this.unpaidOrderService = unpaidOrderService;
        this.opsExceptionService = opsExceptionService;
        this.taskService = taskService;
    }

    @Scheduled(fixedDelay = 120_000)
    public void run() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 300)) {
            return;
        }
        boolean failed = false;
        String summary = "无待对账扣款";
        try {
            summary = reconcile();
        } catch (Exception e) {
            failed = true;
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
            }
        }
    }

    private String reconcile() {
        Instant quietBefore = Instant.now().minusSeconds(QUIET_SECONDS);
        List<PaymentOperation> pending = paymentOperationMapper.findStaleChargePending(quietBefore, 20);
        if (pending.isEmpty()) {
            return "无待对账扣款";
        }
        int closed = 0;
        int failed = 0;
        int unknown = 0;
        int legacy = 0;
        for (PaymentOperation trace : pending) {
            try {
                if (trace.getAttemptedChannel() == null || trace.getAttemptedChannel().isBlank()
                        || trace.getChargeCents() == null) {
                    legacy++;
                    alertUnknown(trace, "痕迹缺少尝试渠道/金额");
                    continue;
                }
                OrderPaymentService.ChargeQueryResult result = orderPaymentService.queryPendingCharge(trace);
                switch (result.status()) {
                    case SUCCESS -> {
                        if (unpaidOrderService.reconcileChargePending(
                                trace.getOrderId(), trace.getAmountCents(),
                                trace.getAttemptedChannel(), trace.getChargeCents(), result.tradeNo())) {
                            closed++;
                        } else {
                            unknown++;
                        }
                    }
                    case FAILED -> {
                        orderPaymentService.finalizeChargePending(trace.getOrderId(), null);
                        failed++;
                    }
                    case UNKNOWN -> {
                        unknown++;
                        alertUnknown(trace, "渠道查单仍未返回终态");
                    }
                }
            } catch (Exception e) {
                unknown++;
                log.warn("charge pending reconcile failed orderId={}: {}", trace.getOrderId(), e.getMessage());
            }
        }
        return "扣款对账 收口 " + closed + " 笔，终结 " + failed + " 笔，未知 " + unknown
                + " 笔，旧痕迹 " + legacy + " 笔";
    }

    private void alertUnknown(PaymentOperation trace, String detail) {
        Instant createdAt = trace.getCreatedAt() != null ? trace.getCreatedAt() : trace.getUpdatedAt();
        if (createdAt == null) {
            return;
        }
        long ageMinutes = Duration.between(createdAt, Instant.now()).toMinutes();
        if (ageMinutes < FIRST_ALERT_MINUTES
                || ageMinutes >= FIRST_ALERT_MINUTES + FIRST_ALERT_WINDOW_MINUTES) {
            return;
        }
        opsExceptionService.report(
                "CHARGE_PENDING_UNRESOLVED",
                "HIGH",
                new OpsExceptionService.ExceptionReport.ExceptionRefs(
                        null, null, trace.getOrderId(), trace.getUserId()),
                "渠道扣款结果仍未确认",
                "订单 " + trace.getOrderId() + " 的 CHARGE_PENDING 已超过 " + ageMinutes
                        + " 分钟（渠道=" + trace.getAttemptedChannel() + "，尝试额(分)="
                        + trace.getChargeCents() + "）；" + detail + "，请人工核对渠道结果");
    }
}
