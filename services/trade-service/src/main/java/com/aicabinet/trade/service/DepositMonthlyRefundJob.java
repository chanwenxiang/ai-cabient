package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 押金按月退还（V360，对齐老系统 MachineGuaranteeCashTask）：每月 1 日 01:30 执行。 */
@Component
public class DepositMonthlyRefundJob {
    private static final Logger log = LoggerFactory.getLogger(DepositMonthlyRefundJob.class);
    private static final String TASK_KEY = "deposit-monthly-refund";

    private final DeviceRentalService rentalService;
    private final ScheduledTaskService taskService;

    public DepositMonthlyRefundJob(DeviceRentalService rentalService, ScheduledTaskService taskService) {
        this.rentalService = rentalService;
        this.taskService = taskService;
    }

    @Scheduled(cron = "${aicabinet.deposit-refund.cron:0 30 1 1 * ?}",
            zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    public void refundMonthly() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "押金月退还未执行";
        try {
            int processed = rentalService.runMonthlyRefunds();
            summary = "押金月退还 " + processed + " 笔";
            log.info("deposit monthly refund done: {}", summary);
        } catch (Exception e) {
            failed = true;
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            log.error("deposit monthly refund failed", e);
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
            }
        }
    }
}
