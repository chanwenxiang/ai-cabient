package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchOutboxScheduler.class);
    private static final String TASK_KEY = "notification-outbox-retry";

    private final NotificationDispatchOutboxService outboxService;
    private final ScheduledTaskService taskService;

    public NotificationDispatchOutboxScheduler(NotificationDispatchOutboxService outboxService,
                                               ScheduledTaskService taskService) {
        this.outboxService = outboxService;
        this.taskService = taskService;
    }

    @Scheduled(fixedDelayString = "${aicabinet.notify.outbox-retry-delay-ms:60000}")
    public void retry() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 300)) {
            return;
        }
        boolean failed = false;
        String summary = "通知重试队列无待办";
        try {
            int sent = outboxService.dispatchDue();
            summary = sent == 0 ? "通知重试队列无待办" : "通知重试队列发送 " + sent + " 条";
        } catch (Exception e) {
            failed = true;
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            log.error("notification outbox retry failed", e);
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
            }
        }
    }
}
