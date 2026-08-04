package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiryAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryAlertScheduler.class);

    private final InventoryLotService inventoryLotService;

    public ExpiryAlertScheduler(InventoryLotService inventoryLotService) {
        this.inventoryLotService = inventoryLotService;
    }

    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void scanExpiry() {
        int alerts = inventoryLotService.scanExpiryAlerts();
        if (alerts > 0) {
            log.info("expiry scan created/updated alerts={}", alerts);
        }
    }
}
