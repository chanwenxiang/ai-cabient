package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 每日凌晨固化前一日毛利快照（改成本不回溯）。 */
@Component
public class FinanceMarginLockScheduler {
    private static final String FINANCE_MARGIN = "finance-margin";


    private static final Logger log = LoggerFactory.getLogger(FinanceMarginLockScheduler.class);

    private final FundBillService fundBillService;
    private final ScheduledTaskService taskService;

    public FinanceMarginLockScheduler(FundBillService fundBillService,
                                      ScheduledTaskService taskService) {
        this.fundBillService = fundBillService;
        this.taskService = taskService;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void solidifyYesterday() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(FINANCE_MARGIN, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "本次未固化毛利快照";
        try {
            LocalDate yesterday = LocalDate.now(ScheduleZones.ZONE).minusDays(1);
            var dto = fundBillService.solidifyMargin(null, yesterday);
            summary = "已固化 " + yesterday + " 毛利快照，订单 " + dto.orderCount() + " 笔";
            log.info("finance margin solidified for {}", yesterday);
        } catch (Exception e) {
            failed = true;
            taskService.finish(FINANCE_MARGIN, "FAILED", e.getMessage(), start);
            log.warn("finance margin solidify failed", e);
        } finally {
            if (!failed) {
                taskService.finish(FINANCE_MARGIN, "SUCCESS", summary, start);
            }
        }
    }
}
