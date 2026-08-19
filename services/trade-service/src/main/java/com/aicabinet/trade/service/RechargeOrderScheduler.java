package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RechargeOrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(RechargeOrderScheduler.class);

    private final PaymentService paymentService;

    @Autowired
    private ScheduledTaskService taskService;

    public RechargeOrderScheduler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.recharge.auto-cancel-interval-ms:300000}", initialDelay = 120_000)
    public void autoCancelExpired() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("recharge-cancel", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无超时充值单";
        try {
            int n = paymentService.autoCancelExpiredPending();
            summary = n <= 0 ? "本次无超时充值单" : "取消超时充值单 " + n + " 笔";
            if (n > 0) {
                log.info("recharge auto-cancel finished count={}", n);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("recharge-cancel", "FAILED", e.getMessage(), start);
            log.warn("recharge auto-cancel failed", e);
        } finally {
            if (!failed) {
                taskService.finish("recharge-cancel", "SUCCESS", summary, start);
            }
        }
    }
}
