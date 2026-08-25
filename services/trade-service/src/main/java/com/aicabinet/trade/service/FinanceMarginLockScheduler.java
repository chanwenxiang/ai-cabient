package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/** 每日凌晨固化前一日毛利快照（改成本不回溯）。 */
@Component
public class FinanceMarginLockScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinanceMarginLockScheduler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final FundBillService fundBillService;

    @Autowired
    private ScheduledTaskService taskService;

    public FinanceMarginLockScheduler(FundBillService fundBillService) {
        this.fundBillService = fundBillService;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Shanghai")
    public void solidifyYesterday() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("finance-margin", 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "本次未固化毛利快照";
        try {
            LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
            var dto = fundBillService.solidifyMargin(null, yesterday);
            summary = "已固化 " + yesterday + " 毛利快照，订单 " + dto.orderCount() + " 笔";
            log.info("finance margin solidified for {}", yesterday);
        } catch (Exception e) {
            failed = true;
            taskService.finish("finance-margin", "FAILED", e.getMessage(), start);
            log.warn("finance margin solidify failed: {}", e.getMessage());
        } finally {
            if (!failed) {
                taskService.finish("finance-margin", "SUCCESS", summary, start);
            }
        }
    }
}
