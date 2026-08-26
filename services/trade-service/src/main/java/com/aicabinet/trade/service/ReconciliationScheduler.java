package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ReconciliationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ReconciliationScheduler {
    private static final String RECONCILIATION = "reconciliation";


    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationProperties properties;

    @Autowired
    private ScheduledTaskService taskService;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   ReconciliationProperties properties) {
        this.reconciliationService = reconciliationService;
        this.properties = properties;
    }

    @Scheduled(cron = "${aicabinet.reconciliation.scheduled-cron:0 30 1 * * *}")
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
            LocalDate yesterday = LocalDate.now().minusDays(1);
            var dto = reconciliationService.runDaily(null, yesterday, "WECHAT");
            summary = "对账 " + yesterday + " 完成，匹配 " + dto.matchedCount()
                    + " 笔，未匹配 " + dto.unmatchedCount() + " 笔，状态 " + dto.status();
            log.info("scheduled reconciliation completed for date={}", yesterday);
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
}
