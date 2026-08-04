package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ReconciliationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final ReconciliationProperties properties;

    public ReconciliationScheduler(ReconciliationService reconciliationService,
                                   ReconciliationProperties properties) {
        this.reconciliationService = reconciliationService;
        this.properties = properties;
    }

    @Scheduled(cron = "${aicabinet.reconciliation.scheduled-cron:0 30 1 * * *}")
    public void runDailyReconciliation() {
        if (!properties.scheduledEnabled()) {
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            reconciliationService.runDaily(null, yesterday, "WECHAT");
            log.info("scheduled reconciliation completed for date={}", yesterday);
        } catch (Exception e) {
            log.error("scheduled reconciliation failed for date={}", yesterday, e);
        }
    }
}
