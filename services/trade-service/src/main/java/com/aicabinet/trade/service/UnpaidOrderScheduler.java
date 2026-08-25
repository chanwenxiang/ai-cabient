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
        String summary = "本次无超时未付订单";
        try {
            int n = unpaidOrderService.autoCancelExpired();
            summary = n <= 0 ? "本次无超时未付订单" : "取消超时未付订单 " + n + " 笔";
            if (n > 0) {
                log.info("unpaid auto-cancel finished count={}", n);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("unpaid-cancel", "FAILED", e.getMessage(), start);
            log.warn("unpaid auto-cancel failed", e);
        } finally {
            if (!failed) {
                taskService.finish("unpaid-cancel", "SUCCESS", summary, start);
            }
        }
    }
}
