package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/** 机器账单月度出账（V356）：每月 1 日 01:10 出上月账（付场地方，线下结算）。 */
@Component
public class MachinePayeeBillJob {
    private static final Logger log = LoggerFactory.getLogger(MachinePayeeBillJob.class);
    private static final String TASK_KEY = "machine-payee-bill";

    private final MachinePayeeBillService billService;
    private final ScheduledTaskService taskService;

    public MachinePayeeBillJob(MachinePayeeBillService billService, ScheduledTaskService taskService) {
        this.billService = billService;
        this.taskService = taskService;
    }

    @Scheduled(cron = "${aicabinet.machine-payee-bill.cron:0 10 1 1 * ?}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void generateMonthlyBills() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "机器账单出账未执行";
        try {
            YearMonth lastMonth = YearMonth.now(ScheduleZones.ZONE).minusMonths(1);
            int created = billService.generateMonthly(lastMonth);
            summary = "出 " + lastMonth + " 账单 " + created + " 笔";
            log.info("machine payee bill job done: {}", summary);
        } catch (Exception e) {
            failed = true;
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            log.error("machine payee bill job failed", e);
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
            }
        }
    }
}
