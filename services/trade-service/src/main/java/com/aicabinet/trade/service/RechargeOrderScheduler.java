package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RechargeOrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(RechargeOrderScheduler.class);

    private final PaymentService paymentService;

    public RechargeOrderScheduler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.recharge.auto-cancel-interval-ms:300000}", initialDelay = 120_000)
    public void autoCancelExpired() {
        try {
            int n = paymentService.autoCancelExpiredPending();
            if (n > 0) {
                log.info("recharge auto-cancel finished count={}", n);
            }
        } catch (Exception ex) {
            log.warn("recharge auto-cancel failed", ex);
        }
    }
}
