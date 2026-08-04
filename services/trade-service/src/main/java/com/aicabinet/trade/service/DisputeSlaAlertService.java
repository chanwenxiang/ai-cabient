package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class DisputeSlaAlertService {

    private static final Logger log = LoggerFactory.getLogger(DisputeSlaAlertService.class);

    private final DisputeSlaProperties disputeSlaProperties;
    private final RestClient restClient;

    public DisputeSlaAlertService(DisputeSlaProperties disputeSlaProperties) {
        this.disputeSlaProperties = disputeSlaProperties;
        this.restClient = RestClient.create();
    }

    public void sendReminder(DisputeTicket ticket) {
        String msg = String.format("[争议SLA提醒] 工单 %s 会话 %s 将在 %dh 内到期，请尽快审核",
                ticket.getTicketId(), ticket.getSessionId(), disputeSlaProperties.reminderHoursBefore());
        log.warn(msg);
        postWebhook("DISPUTE_SLA_REMINDER", ticket, msg);
    }

    public void sendOverdue(DisputeTicket ticket) {
        String msg = String.format("[争议SLA超时] 工单 %s 会话 %s 已超过 %dh 未结案",
                ticket.getTicketId(), ticket.getSessionId(), disputeSlaProperties.hours());
        log.error(msg);
        postWebhook("DISPUTE_SLA_OVERDUE", ticket, msg);
    }

    private void postWebhook(String type, DisputeTicket ticket, String message) {
        String url = disputeSlaProperties.alertWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "type", type,
                            "ticketId", ticket.getTicketId(),
                            "sessionId", ticket.getSessionId(),
                            "reason", ticket.getReason() != null ? ticket.getReason() : "",
                            "message", message
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("dispute sla webhook failed ticket={}: {}", ticket.getTicketId(), e.getMessage());
        }
    }
}
