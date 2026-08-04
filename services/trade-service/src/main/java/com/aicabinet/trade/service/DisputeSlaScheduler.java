package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
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

    @Scheduled(fixedRate = 900_000)
    @Transactional
    public void checkDisputeSla() {
        if (!disputeSlaProperties.schedulerEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant reminderThreshold = now.plus(disputeSlaProperties.reminderHoursBefore(), ChronoUnit.HOURS);

        List<DisputeTicket> openTickets = disputeRepository.findOpenNeedingSlaScan(SCAN_BATCH);
        int reminders = 0;
        int overdue = 0;
        for (DisputeTicket ticket : openTickets) {
            boolean dirty = false;
            if (ticket.getSlaDueAt() == null) {
                ticket.setSlaDueAt(ticket.getCreatedAt().plus(disputeSlaProperties.hours(), ChronoUnit.HOURS));
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
        if (reminders > 0 || overdue > 0) {
            log.info("dispute sla scan reminders={} overdue={} scanned={}", reminders, overdue, openTickets.size());
        }
    }
}
