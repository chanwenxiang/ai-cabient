package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MerchantWorkbenchNotifyScheduler {

    private static final Logger log = LoggerFactory.getLogger(MerchantWorkbenchNotifyScheduler.class);

    private final MerchantNotifyService merchantNotifyService;

    @Autowired
    private ScheduledTaskService taskService;

    public MerchantWorkbenchNotifyScheduler(MerchantNotifyService merchantNotifyService) {
        this.merchantNotifyService = merchantNotifyService;
    }

    /** 每 15 分钟检查一次商户待办并推送订阅消息（已绑定微信且开启偏好） */
    @Scheduled(fixedRate = 900_000, initialDelay = 120_000)
    public void pushWorkbenchAlerts() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("merchant-notify", 600)) {
            return;
        }
        boolean failed = false;
        try {
            try {
                merchantNotifyService.dispatchWorkbenchAlerts();
            } catch (Exception ex) {
                log.warn("merchant workbench notify scheduler failed", ex);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("merchant-notify", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("merchant-notify", "SUCCESS", null, start);
            }
        }
    }
}
