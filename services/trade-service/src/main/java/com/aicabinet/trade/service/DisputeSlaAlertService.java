package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DisputeSlaAlertService {

    private static final Logger log = LoggerFactory.getLogger(DisputeSlaAlertService.class);

    private final DisputeSlaProperties disputeSlaProperties;
    private final OpsAlertDispatcher opsAlertDispatcher;

    @Autowired
    private SystemConfigService systemConfigService;

    public DisputeSlaAlertService(DisputeSlaProperties disputeSlaProperties,
                                  OpsAlertDispatcher opsAlertDispatcher) {
        this.disputeSlaProperties = disputeSlaProperties;
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    public void sendReminder(DisputeTicket ticket) {
        String title = "[争议SLA提醒]";
        String msg = String.format("工单 %s 会话 %s 将在 %dh 内到期，请尽快审核",
                ticket.getTicketId(), ticket.getSessionId(),
                systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_REMINDER_HOURS,
                        disputeSlaProperties.reminderHoursBefore()));
        log.warn(title + " " + msg);
        dispatch("DISPUTE_SLA_REMINDER", title, ticket, msg);
    }

    public void sendOverdue(DisputeTicket ticket) {
        String title = "[争议SLA超时]";
        String msg = String.format("工单 %s 会话 %s 已超过 %dh 未结案",
                ticket.getTicketId(), ticket.getSessionId(),
                systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_HOURS,
                        disputeSlaProperties.hours()));
        log.error(title + " " + msg);
        dispatch("DISPUTE_SLA_OVERDUE", title, ticket, msg);
    }

    private void dispatch(String type, String title, DisputeTicket ticket, String message) {
        Map<String, Object> extra = Map.of(
                "ticketId", ticket.getTicketId(),
                "sessionId", ticket.getSessionId(),
                "reason", ticket.getReason() != null ? ticket.getReason() : ""
        );
        String legacyUrl = systemConfigService.getValue(SystemConfigService.DISPUTE_SLA_WEBHOOK,
                disputeSlaProperties.alertWebhookUrl());
        opsAlertDispatcher.send(type, title, message, extra, legacyUrl);
    }
}
