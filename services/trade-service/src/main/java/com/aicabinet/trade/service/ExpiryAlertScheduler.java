package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiryAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryAlertScheduler.class);

    private final InventoryLotService inventoryLotService;

    @Autowired
    private ScheduledTaskService taskService;

    public ExpiryAlertScheduler(InventoryLotService inventoryLotService) {
        this.inventoryLotService = inventoryLotService;
    }

    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void scanExpiry() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("expiry-alert", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无临期预警";
        try {
            int alerts = inventoryLotService.scanExpiryAlerts();
            summary = alerts <= 0 ? "本次无临期预警" : "临期/过期预警 " + alerts + " 条";
            if (alerts > 0) {
                log.info("expiry scan created/updated alerts={}", alerts);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("expiry-alert", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("expiry-alert", "SUCCESS", summary, start);
            }
        }
    }
}
