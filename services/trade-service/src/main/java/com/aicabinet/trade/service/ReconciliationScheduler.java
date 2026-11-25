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
    /** 备付不足/NOT_ENOUGH 提现单恢复重试。 */
    private static final String WITHDRAW_FUNDING_RETRY = "withdraw-funding-retry";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationProperties properties;
    private final ScheduledTaskService taskService;
    private final WeChatRefundReconciler weChatRefundReconciler;
    private final MerchantWithdrawService merchantWithdrawService;
    private final LineWithdrawService lineWithdrawService;
    private final PlatformFundBillReconciler platformFundBillReconciler;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   ReconciliationProperties properties,
                                   ScheduledTaskService taskService,
                                   WeChatRefundReconciler weChatRefundReconciler,
                                   MerchantWithdrawService merchantWithdrawService,
                                   LineWithdrawService lineWithdrawService,
                                   PlatformFundBillReconciler platformFundBillReconciler) {
        this.reconciliationService = reconciliationService;
        this.properties = properties;
        this.taskService = taskService;
        this.weChatRefundReconciler = weChatRefundReconciler;
        this.merchantWithdrawService = merchantWithdrawService;
        this.lineWithdrawService = lineWithdrawService;
        this.platformFundBillReconciler = platformFundBillReconciler;
    }

    // 2026-11-23 实证：01:30 拉「昨天」的微信 tradebill 会 500（账单次日 9 点后才生成）。
    // 主跑改 10:30；14:30 再跑一次作为失败重试（runDaily 按 (date,channel) 幂等，重跑只更新主行）。
    @Scheduled(
            cron = "${aicabinet.reconciliation.scheduled-cron:0 30 10,14 * * *}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void runDailyReconciliation() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(RECONCILIATION, 1800)) {
            return;
        }
        boolean failed = false;
        // F1（收敛计划 2026-11-17）：「开关关掉」与「真跑成功」必须落成不同结果。
        // 此前两者都写 SUCCESS，运营台看起来对账每天正常，实际一笔都没对 —— 而它恰恰是
        // 「渠道已扣、本地未记」的唯一日级收口点。结果串改成 SKIPPED 后，运营台/看护能区分；
        // 注意仍走 finish（推进 last_run_at），因为这是**运维有意关闭**，不是停摆，不该报 OVERDUE。
        String summary = "对账调度未启用";
        if (!properties.scheduledEnabled()) {
            summary = "对账调度未启用（aicabinet.reconciliation.scheduled-enabled=false）—— 当日未执行任何对账";
            log.warn("scheduled reconciliation is disabled by config: {}", summary);
            taskService.finish(RECONCILIATION, "SKIPPED", summary, start);
            return;
        }
        try {
            LocalDate yesterday = LocalDate.now(ScheduleZones.ZONE).minusDays(1);
            var dto = reconciliationService.runDaily(null, yesterday, "WECHAT");
            summary = "对账 " + yesterday + " 完成，匹配 " + dto.matchedCount()
                    + " 笔，未匹配 " + dto.unmatchedCount() + " 笔，状态 " + dto.status();
            var fundBill = platformFundBillReconciler.reconcile(yesterday);
            summary += "；运营账户资金账单：" + fundBill.message();
            log.info("scheduled reconciliation completed for date={}", yesterday);
        } catch (Exception e) {
            failed = true;
            summary = "对账失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.error("scheduled reconciliation failed", e);
        }
        try {
            int advanced = weChatRefundReconciler.advanceProcessingRefunds();
            if (advanced > 0) {
                summary += "；推进 PROCESSING 退款 " + advanced + " 笔";
            }
        } catch (Exception e) {
            failed = true;
            summary += "；PROCESSING 退款推进失败：" + e.getMessage();
            log.error("scheduled refund advancement failed", e);
        }
        taskService.finish(RECONCILIATION, failed ? "FAILED" : "SUCCESS", summary, start);
    }

    /**
     * 每小时重试备付失败单。渠道结果作为权威，NOT_ENOUGH 自动排下一小时。
     */
    @Scheduled(cron = "0 20 * * * ?", zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void retryFundingBlockedWithdraws() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(WITHDRAW_FUNDING_RETRY, 900)) {
            return;
        }
        try {
            int attempted = merchantWithdrawService.retryFundingBlockedWithdraws();
            taskService.finish(WITHDRAW_FUNDING_RETRY, "SUCCESS",
                    "备付恢复自动重试 " + attempted + " 单（渠道结果裁决）", start);
        } catch (Exception e) {
            taskService.finish(WITHDRAW_FUNDING_RETRY, "FAILED", e.getMessage(), start);
            log.error("funding retry sweep failed", e);
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
