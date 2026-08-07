package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UnpaidOrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderScheduler.class);

    private final UnpaidOrderService unpaidOrderService;

    @Autowired
    private ScheduledTaskService taskService;

    public UnpaidOrderScheduler(UnpaidOrderService unpaidOrderService) {
        this.unpaidOrderService = unpaidOrderService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.unpaid.auto-cancel-interval-ms:900000}", initialDelay = 180_000)
    public void autoCancelExpired() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("unpaid-cancel", 600)) {
            return;
        }
        boolean failed = false;
        try {
            try {
                int n = unpaidOrderService.autoCancelExpired();
                if (n > 0) {
                    log.info("unpaid auto-cancel finished count={}", n);
                }
            } catch (Exception ex) {
                log.warn("unpaid auto-cancel failed", ex);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("unpaid-cancel", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("unpaid-cancel", "SUCCESS", null, start);
            }
        }
    }
}
