package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DisputeTicketDto;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.repository.DisputeTicketRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DisputeService {

    private final DisputeTicketRepository disputeRepository;
    private final ShoppingSessionRepository sessionRepository;
    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;
    private final MinioVideoService minioVideoService;
    private final AdminAuditService auditService;
    private final RiskControlService riskControlService;
    private final PermissionService permissionService;

    public DisputeService(DisputeTicketRepository disputeRepository,
                          ShoppingSessionRepository sessionRepository,
                          SettlementService settlementService,
                          ObjectMapper objectMapper,
                          MinioVideoService minioVideoService,
                          AdminAuditService auditService,
                          RiskControlService riskControlService,
                          PermissionService permissionService) {
        this.disputeRepository = disputeRepository;
        this.sessionRepository = sessionRepository;
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
        this.minioVideoService = minioVideoService;
        this.auditService = auditService;
        this.riskControlService = riskControlService;
        this.permissionService = permissionService;
    }

    @Transactional
    public DisputeTicketDto createTicket(ShoppingSession session,
                                         VisionServiceClient.RecognitionResult recognition,
                                         String reason) {
        if (disputeRepository.findBySessionId(session.getSessionId()).isPresent()) {
            return toDto(disputeRepository.findBySessionId(session.getSessionId()).get());
        }
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("D" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        ticket.setSessionId(session.getSessionId());
        ticket.setReason(reason);
        ticket.setStatus("OPEN");
        ticket.setItems(toJson(recognition.items()));
        disputeRepository.save(ticket);
        riskControlService.onDisputeCreated(session.getUserId(), session.getSessionId());
        return toDto(ticket);
    }

    @Transactional(readOnly = true)
    public List<DisputeTicketDto> listMyTickets(Long userId) {
        return disputeRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<DisputeTicketDto> listOpenTickets(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:dispute");
        return disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN")
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public OrderDto resolveTicket(Long operatorId, String ticketId, ResolveDisputeRequest request) {
        permissionService.requirePermission(operatorId, "ops:dispute");
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        if (!"OPEN".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.TICKET_ALREADY_RESOLVED);
        }
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));

        var manualItems = request.items().stream()
                .map(i -> new VisionServiceClient.RecognizedItem(i.skuId(), i.quantity(), 1.0f))
                .toList();
        OrderDto order = settlementService.settleManual(session, manualItems);

        ticket.setStatus("RESOLVED");
        ticket.setResolutionItems(toJson(manualItems));
        ticket.setResolvedAt(Instant.now());
        disputeRepository.save(ticket);

        session.setOrderId(order.orderId());
        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        auditService.record(operatorId, "DISPUTE_RESOLVE", "DISPUTE", ticketId,
                "order=" + order.orderId() + " items=" + request.items().size());
        return order;
    }

    private DisputeTicketDto toDto(DisputeTicket ticket) {
        List<OrderLineDto> suggested = parseItems(ticket.getItems());
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        String videoUri = session != null ? session.getVideoUri() : null;
        String previewUrl = minioVideoService.presignPlaybackUrl(videoUri).orElse(null);
        return new DisputeTicketDto(
                ticket.getTicketId(), ticket.getSessionId(), ticket.getReason(),
                ticket.getStatus(), suggested, ticket.getCreatedAt(),
                videoUri, previewUrl);
    }

    private List<OrderLineDto> parseItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<VisionServiceClient.RecognizedItem> items = objectMapper.readValue(json,
                    new TypeReference<>() {});
            return items.stream()
                    .map(i -> new OrderLineDto(i.skuId(), i.skuId(), i.quantity(), 0, 0))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
