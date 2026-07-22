package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UnpaidOrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderScheduler.class);

    private final UnpaidOrderService unpaidOrderService;

    public UnpaidOrderScheduler(UnpaidOrderService unpaidOrderService) {
        this.unpaidOrderService = unpaidOrderService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.unpaid.auto-cancel-interval-ms:900000}", initialDelay = 180_000)
    public void autoCancelExpired() {
        try {
            int n = unpaidOrderService.autoCancelExpired();
            if (n > 0) {
                log.info("unpaid auto-cancel finished count={}", n);
            }
        } catch (Exception ex) {
            log.warn("unpaid auto-cancel failed", ex);
        }
    }
}
