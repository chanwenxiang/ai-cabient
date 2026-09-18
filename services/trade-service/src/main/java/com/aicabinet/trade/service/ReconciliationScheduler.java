package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ReconciliationProperties;
import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ReconciliationScheduler {
    private static final String RECONCILIATION = "reconciliation";
    /** 提现 PAYING 超时兜底扫描任务 key（H38）。 */
    private static final String WITHDRAW_PAYING_TIMEOUT = "withdraw-paying-timeout";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationProperties properties;
    private final ScheduledTaskService taskService;
    private final WeChatRefundReconciler weChatRefundReconciler;
    private final MerchantWithdrawService merchantWithdrawService;
    private final LineWithdrawService lineWithdrawService;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   ReconciliationProperties properties,
                                   ScheduledTaskService taskService,
                                   WeChatRefundReconciler weChatRefundReconciler,
                                   MerchantWithdrawService merchantWithdrawService,
                                   LineWithdrawService lineWithdrawService) {
        this.reconciliationService = reconciliationService;
        this.properties = properties;
        this.taskService = taskService;
        this.weChatRefundReconciler = weChatRefundReconciler;
        this.merchantWithdrawService = merchantWithdrawService;
        this.lineWithdrawService = lineWithdrawService;
    }

    @Scheduled(
            cron = "${aicabinet.reconciliation.scheduled-cron:0 30 1 * * *}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void runDailyReconciliation() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(RECONCILIATION, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "对账调度未启用";
        try {
            if (!properties.scheduledEnabled()) {
                summary = "对账调度未启用";
                return;
            }
            LocalDate yesterday = LocalDate.now(ScheduleZones.ZONE).minusDays(1);
            var dto = reconciliationService.runDaily(null, yesterday, "WECHAT");
            summary = "对账 " + yesterday + " 完成，匹配 " + dto.matchedCount()
                    + " 笔，未匹配 " + dto.unmatchedCount() + " 笔，状态 " + dto.status();
            log.info("scheduled reconciliation completed for date={}", yesterday);
            // H42(c): 对账后推进 PROCESSING 微信退款状态（查单兜底，无退款回调入口）
            int advanced = weChatRefundReconciler.advanceProcessingRefunds();
            if (advanced > 0) {
                summary += "；推进 PROCESSING 退款 " + advanced + " 笔";
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(RECONCILIATION, "FAILED", e.getMessage(), start);
            log.error("scheduled reconciliation failed", e);
        } finally {
            if (!failed) {
                taskService.finish(RECONCILIATION, "SUCCESS", summary, start);
            }
        }
    }

    /**
     * 提现打款卡 PAYING 兜底（H38）：PAYING 且 updatedAt 超过 1 小时的提现单
     * 置 FAILED 并解冻（商户/线长两类），之后可人工重试打款。
     */
    @Scheduled(fixedDelay = 600_000, zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void failStalePayingWithdraws() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(WITHDRAW_PAYING_TIMEOUT, 600)) {
            return;
        }
        int merchant = 0;
        int line = 0;
        try {
            merchant = merchantWithdrawService.failStalePayingWithdraws();
            line = lineWithdrawService.failStalePayingWithdraws();
        } catch (Exception e) {
            taskService.finish(WITHDRAW_PAYING_TIMEOUT, "FAILED", e.getMessage(), start);
            log.error("stale PAYING withdraw sweep failed", e);
            return;
        }
        if (merchant > 0 || line > 0) {
            log.info("stale PAYING withdraws auto-failed: merchant={} line={}", merchant, line);
        }
        taskService.finish(WITHDRAW_PAYING_TIMEOUT, "SUCCESS",
                "商户 " + merchant + " 单，线长 " + line + " 单", start);
    }
}
