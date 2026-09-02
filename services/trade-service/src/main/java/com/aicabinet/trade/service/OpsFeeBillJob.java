package com.aicabinet.trade.service;

import com.aicabinet.trade.config.FeeBillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 每月自动出账：场地租金 + 柜机流量费。账期/cron/开关均来自 {@link FeeBillProperties}。
 */
@Service
@ConditionalOnProperty(prefix = "aicabinet.fee-bill", name = "auto-generate-enabled", havingValue = "true", matchIfMissing = true)
public class OpsFeeBillJob {

    private static final Logger log = LoggerFactory.getLogger(OpsFeeBillJob.class);
    private static final String TASK_KEY = "ops-fee-bill-monthly";

    private final SiteRentBillService rentBillService;
    private final DeviceDataFeeBillService dataFeeBillService;
    private final FeeBillMonthResolver monthResolver;
    private final ScheduledTaskService taskService;

    public OpsFeeBillJob(SiteRentBillService rentBillService,
                         DeviceDataFeeBillService dataFeeBillService,
                         FeeBillMonthResolver monthResolver,
                         ScheduledTaskService taskService) {
        this.rentBillService = rentBillService;
        this.dataFeeBillService = dataFeeBillService;
        this.monthResolver = monthResolver;
        this.taskService = taskService;
    }

    @Scheduled(cron = "${aicabinet.fee-bill.auto-generate-cron:0 30 1 1 * *}",
            zone = "${aicabinet.fee-bill.zone:Asia/Shanghai}")
    public void generateMonthlyFees() {
        long taskStart = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 1800)) {
            return;
        }
        String month = monthResolver.resolve(null);
        String summary = "账期 " + month + " 无新增";
        boolean failed = false;
        try {
            int rent = rentBillService.autoGenerate(month).size();
            int data = dataFeeBillService.autoGenerate(month).size();
            summary = "账期 " + month + " 租金账单 " + rent + " 条，流量费账单 " + data + " 条";
            log.info("ops fee bill job done: {}", summary);
        } catch (Exception e) {
            failed = true;
            log.error("ops fee bill job failed month={}", month, e);
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), taskStart);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, taskStart);
            }
        }
    }
}
