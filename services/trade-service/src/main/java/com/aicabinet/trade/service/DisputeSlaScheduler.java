package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class DisputeSlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(DisputeSlaScheduler.class);
    private static final int SCAN_BATCH = 500;

    private final DisputeSlaProperties disputeSlaProperties;
    private final DisputeTicketMapper disputeRepository;
    private final DisputeSlaAlertService alertService;

    public DisputeSlaScheduler(DisputeSlaProperties disputeSlaProperties,
                                 DisputeTicketMapper disputeRepository,
                                 DisputeSlaAlertService alertService) {
        this.disputeSlaProperties = disputeSlaProperties;
        this.disputeRepository = disputeRepository;
        this.alertService = alertService;
    }

    @Autowired
    private ScheduledTaskService taskService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Scheduled(fixedRate = 900_000)
    @Transactional
    public void checkDisputeSla() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("dispute-sla", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "争议 SLA 扫描未启用";
        try {
        if (!disputeSlaProperties.schedulerEnabled()) {
            summary = "争议 SLA 调度未启用";
            return;
        }
        Instant now = Instant.now();
        Instant reminderThreshold = now.plus(
                systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_REMINDER_HOURS,
                        disputeSlaProperties.reminderHoursBefore()),
                ChronoUnit.HOURS);

        List<DisputeTicket> openTickets = disputeRepository.findOpenNeedingSlaScan(SCAN_BATCH);
        int reminders = 0;
        int overdue = 0;
        for (DisputeTicket ticket : openTickets) {
            boolean dirty = false;
            if (ticket.getSlaDueAt() == null) {
                ticket.setSlaDueAt(ticket.getCreatedAt().plus(
                        systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_HOURS,
                                disputeSlaProperties.hours()),
                        ChronoUnit.HOURS));
                dirty = true;
            }
            if (ticket.getSlaReminderAt() == null
                    && !ticket.getSlaDueAt().isAfter(reminderThreshold)
                    && ticket.getSlaDueAt().isAfter(now)) {
                alertService.sendReminder(ticket);
                ticket.setSlaReminderAt(now);
                dirty = true;
                reminders++;
            }
            if (ticket.getSlaAlertedAt() == null && !ticket.getSlaDueAt().isAfter(now)) {
                alertService.sendOverdue(ticket);
                ticket.setSlaAlertedAt(now);
                dirty = true;
                overdue++;
            }
            if (dirty) {
                disputeRepository.save(ticket);
            }
        }
        summary = "扫描 " + openTickets.size() + " 张，提醒 " + reminders + "，逾期 " + overdue;
        if (reminders > 0 || overdue > 0) {
            log.info("dispute sla scan reminders={} overdue={} scanned={}", reminders, overdue, openTickets.size());
        }
        } catch (Exception e) {
            failed = true;
            taskService.finish("dispute-sla", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("dispute-sla", "SUCCESS", summary, start);
            }
        }
    }
}
