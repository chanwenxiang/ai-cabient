package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DisputeMessageDto;
import com.aicabinet.common.dto.DisputeTicketDto;
import com.aicabinet.common.dto.FileDisputeRequest;
import com.aicabinet.common.dto.MerchantDisputeDetailDto;
import com.aicabinet.common.dto.MerchantReplyDisputeRequest;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.CloseDisputeRequest;
import com.aicabinet.common.dto.ReopenDisputeRequest;
import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.dto.ResolveDisputeResultDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DisputeMessage;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.repository.DisputeMessageRepository;
import com.aicabinet.trade.repository.DisputeTicketRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.repository.SkuCatalogRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DisputeService {

    private final DisputeTicketRepository disputeRepository;
    private final DisputeMessageRepository disputeMessageRepository;
    private final ShoppingSessionRepository sessionRepository;
    private final CabinetOrderRepository orderRepository;
    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;
    private final MinioVideoService minioVideoService;
    private final AdminAuditService auditService;
    private final RiskControlService riskControlService;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final SkuCatalogRepository skuCatalogRepository;
    private final DisputeSlaProperties disputeSlaProperties;
    private final UserInfoRepository userInfoRepository;

    public DisputeService(DisputeTicketRepository disputeRepository,
                          DisputeMessageRepository disputeMessageRepository,
                          ShoppingSessionRepository sessionRepository,
                          CabinetOrderRepository orderRepository,
                          SettlementService settlementService,
                          ObjectMapper objectMapper,
                          MinioVideoService minioVideoService,
                          AdminAuditService auditService,
                          RiskControlService riskControlService,
                          PermissionService permissionService,
                          MerchantScopeService merchantScopeService,
                          MerchantPortalGuard merchantPortalGuard,
                          SkuCatalogRepository skuCatalogRepository,
                          DisputeSlaProperties disputeSlaProperties,
                          UserInfoRepository userInfoRepository) {
        this.disputeRepository = disputeRepository;
        this.disputeMessageRepository = disputeMessageRepository;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
        this.minioVideoService = minioVideoService;
        this.auditService = auditService;
        this.riskControlService = riskControlService;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.skuCatalogRepository = skuCatalogRepository;
        this.disputeSlaProperties = disputeSlaProperties;
        this.userInfoRepository = userInfoRepository;
    }

    @Transactional
    public DisputeTicketDto createTicket(ShoppingSession session,
                                         VisionServiceClient.RecognitionResult recognition,
                                         String reason) {
        return disputeRepository.findBySessionId(session.getSessionId())
                .map(this::toDto)
                .orElseGet(() -> saveOpenTicket(session.getUserId(), session.getSessionId(), reason,
                        toJson(recognition.items()), "RECOGNITION", priorityForRecognition(recognition)));
    }

    @Transactional
    public DisputeTicketDto fileByConsumer(Long userId, FileDisputeRequest request) {
        ShoppingSession session = sessionRepository.findById(request.sessionId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
        if (session.getState() != SessionState.COMPLETED && session.getState() != SessionState.DISPUTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SESSION_STATE_INVALID);
        }
        if (disputeRepository.findBySessionId(session.getSessionId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DISPUTE_ALREADY_EXISTS);
        }
        return saveOpenTicket(userId, session.getSessionId(), request.reason().trim(), "[]",
                normalizeCategory(request.category()), normalizePriority(request.priority()));
    }

    private DisputeTicketDto saveOpenTicket(Long userId, String sessionId, String reason, String itemsJson,
                                            String category, String priority) {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId(newTicketId());
        ticket.setSessionId(sessionId);
        ticket.setReason(reason);
        ticket.setStatus("OPEN");
        ticket.setCategory(category);
        ticket.setPriority(priority);
        ticket.setItems(itemsJson);
        Instant now = Instant.now();
        ticket.setSlaDueAt(now.plus(disputeSlaProperties.hours(), ChronoUnit.HOURS));
        disputeRepository.save(ticket);
        riskControlService.onDisputeCreated(userId, sessionId);
        return toDto(ticket);
    }

    private static String newTicketId() {
        return "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
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

    @Transactional(readOnly = true)
    public PageResult<DisputeTicketDto> listTickets(Long operatorId, int page, int size,
                                                    String status, String sessionId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:dispute");
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, deviceId);
        Page<DisputeTicket> result;
        if (deviceScope != null && deviceScope.isEmpty()) {
            result = Page.empty(pageable);
        } else if (deviceScope != null) {
            result = disputeRepository.searchByDeviceIds(
                    blankToNull(status), blankToNull(sessionId), deviceScope, pageable);
        } else {
            result = disputeRepository.search(
                    blankToNull(status), blankToNull(sessionId), blankToNull(deviceId), pageable);
        }
        return new PageResult<>(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    @Transactional
    public ResolveDisputeResultDto resolveTicket(Long operatorId, String ticketId, ResolveDisputeRequest request) {
        permissionService.requirePermission(operatorId, "ops:dispute");
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        if (!"OPEN".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.TICKET_ALREADY_RESOLVED);
        }
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());

        String resolutionType = normalizeResolutionType(request.resolutionType());
        ResolveDisputeResultDto result = switch (resolutionType) {
            case "KEEP" -> resolveKeep(operatorId, ticket, session);
            case "WAIVE" -> resolveWaive(operatorId, ticket, session);
            case "ADJUST", "CONFIRM" -> resolveConfirm(operatorId, ticket, session, request, resolutionType);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        };

        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        return result;
    }

    @Transactional
    public DisputeTicketDto closeTicket(Long operatorId, String ticketId, CloseDisputeRequest request) {
        permissionService.requirePermission(operatorId, "ops:dispute");
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        if (!"RESOLVED".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only resolved disputes can be closed");
        }
        ticket.setStatus("CLOSED");
        ticket.setClosedAt(Instant.now());
        ticket.setOperatorNote(trimToNull(request != null ? request.note() : null));
        disputeRepository.save(ticket);
        auditService.record(operatorId, "DISPUTE_CLOSE", "DISPUTE", ticketId,
                "session=" + ticket.getSessionId());
        return toDto(ticket);
    }

    @Transactional
    public DisputeTicketDto reopenTicket(Long operatorId, String ticketId, ReopenDisputeRequest request) {
        permissionService.requirePermission(operatorId, "ops:dispute");
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        if ("OPEN".equals(ticket.getStatus())) {
            return toDto(ticket);
        }
        ticket.setStatus("OPEN");
        ticket.setPriority(normalizePriority(request != null ? request.priority() : ticket.getPriority()));
        ticket.setOperatorNote(trimToNull(request != null ? request.note() : null));
        ticket.setClosedAt(null);
        ticket.setReopenedAt(Instant.now());
        if (ticket.getSlaDueAt() == null || !ticket.getSlaDueAt().isAfter(Instant.now())) {
            ticket.setSlaDueAt(Instant.now().plus(disputeSlaProperties.hours(), ChronoUnit.HOURS));
        }
        session.setState(SessionState.DISPUTED);
        sessionRepository.save(session);
        disputeRepository.save(ticket);
        auditService.record(operatorId, "DISPUTE_REOPEN", "DISPUTE", ticketId,
                "session=" + ticket.getSessionId());
        return toDto(ticket);
    }

    private ResolveDisputeResultDto resolveWaive(Long operatorId, DisputeTicket ticket, ShoppingSession session) {
        int refunded = settlementService.waiveAndRefund(session);
        ticket.setStatus("RESOLVED");
        ticket.setResolutionItems("[]");
        ticket.setResolvedAt(Instant.now());
        disputeRepository.save(ticket);
        auditService.record(operatorId, "DISPUTE_WAIVE", "DISPUTE", ticket.getTicketId(),
                "refund=" + refunded);
        String message = refunded > 0
                ? "已免单，退还 ¥" + String.format("%.2f", refunded / 100.0)
                : "已免单，无需扣款";
        return new ResolveDisputeResultDto(null, "WAIVE", refunded, 0, -refunded, message);
    }

    private ResolveDisputeResultDto resolveKeep(Long operatorId, DisputeTicket ticket, ShoppingSession session) {
        int originalAmount = orderRepository.findBySessionId(session.getSessionId())
                .filter(order -> !"REFUNDED".equals(order.getStatus()))
                .map(CabinetOrder::getTotalAmountCents)
                .orElse(0);
        ticket.setStatus("RESOLVED");
        ticket.setResolutionItems(ticket.getItems() != null ? ticket.getItems() : "[]");
        ticket.setResolvedAt(Instant.now());
        disputeRepository.save(ticket);
        auditService.record(operatorId, "DISPUTE_KEEP_BILL", "DISPUTE", ticket.getTicketId(),
                "session=" + ticket.getSessionId() + " amount=" + originalAmount);
        return new ResolveDisputeResultDto(
                null,
                "KEEP", originalAmount, originalAmount, 0, "已复核，维持原账单");
    }

    private ResolveDisputeResultDto resolveConfirm(Long operatorId,
                                                 DisputeTicket ticket,
                                                 ShoppingSession session,
                                                 ResolveDisputeRequest request,
                                                 String resolutionType) {
        var manualItems = request.items().stream()
                .filter(i -> i.quantity() > 0)
                .map(i -> new VisionServiceClient.RecognizedItem(i.skuId(), i.quantity(), 1.0f))
                .toList();
        SettlementService.ConfirmDisputeResult settled =
                settlementService.confirmDisputedItems(session, manualItems);

        ticket.setStatus("RESOLVED");
        ticket.setResolutionItems(toJson(manualItems));
        ticket.setResolvedAt(Instant.now());
        disputeRepository.save(ticket);

        session.setOrderId(settled.order().orderId());
        String message = buildAdjustMessage(settled);
        auditService.record(operatorId, "DISPUTE_RESOLVE", "DISPUTE", ticket.getTicketId(),
                "type=" + resolutionType + " order=" + settled.order().orderId()
                        + " delta=" + settled.adjustmentCents());
        return new ResolveDisputeResultDto(
                settled.order(),
                resolutionType,
                settled.originalAmountCents(),
                settled.finalAmountCents(),
                settled.adjustmentCents(),
                message);
    }

    private static String buildAdjustMessage(SettlementService.ConfirmDisputeResult settled) {
        int delta = settled.adjustmentCents();
        if (settled.originalAmountCents() == 0) {
            return "已确认商品并扣款 ¥" + String.format("%.2f", settled.finalAmountCents() / 100.0);
        }
        if (delta > 0) {
            return "已改单，补扣 ¥" + String.format("%.2f", delta / 100.0);
        }
        if (delta < 0) {
            return "已改单，退还 ¥" + String.format("%.2f", (-delta) / 100.0);
        }
        return "已确认商品，金额不变";
    }

    private static String normalizeResolutionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CONFIRM";
        }
        return switch (raw.trim().toUpperCase()) {
            case "KEEP", "REJECT", "KEEP_BILL" -> "KEEP";
            case "WAIVE", "FREE", "REFUND_ALL" -> "WAIVE";
            case "ADJUST", "补差", "退差" -> "ADJUST";
            case "CONFIRM", "CHARGE" -> "CONFIRM";
            default -> raw.trim().toUpperCase();
        };
    }

    private DisputeTicketDto toDto(DisputeTicket ticket) {
        List<OrderLineDto> suggested = enrichLines(parseItems(ticket.getItems()));
        List<OrderLineDto> resolved = enrichLines(parseItems(ticket.getResolutionItems()));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        String videoUri = session != null ? session.getVideoUri() : null;
        String deviceId = session != null ? session.getDeviceId() : null;
        String sessionState = session != null ? session.getState().name() : null;
        String orderId = session != null ? session.getOrderId() : null;
        Integer billedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .filter(o -> !"REFUNDED".equals(o.getStatus()))
                .map(CabinetOrder::getTotalAmountCents)
                .orElse(null);
        String previewUrl = minioVideoService.presignPlaybackUrl(videoUri).orElse(null);
        Instant now = Instant.now();
        boolean slaOverdue = "OPEN".equals(ticket.getStatus())
                && ticket.getSlaDueAt() != null
                && !ticket.getSlaDueAt().isAfter(now);
        Long slaHoursRemaining = null;
        if ("OPEN".equals(ticket.getStatus()) && ticket.getSlaDueAt() != null && !slaOverdue) {
            slaHoursRemaining = ChronoUnit.HOURS.between(now, ticket.getSlaDueAt());
        }
        return new DisputeTicketDto(
                ticket.getTicketId(), ticket.getSessionId(), deviceId, ticket.getReason(),
                ticket.getStatus(), suggested, resolved, ticket.getCreatedAt(), ticket.getResolvedAt(),
                videoUri, previewUrl, sessionState, orderId, billedAmountCents,
                ticket.getSlaDueAt(), slaOverdue, slaHoursRemaining,
                ticket.getCategory(), ticket.getPriority(), ticket.getOperatorNote(),
                ticket.getClosedAt(), ticket.getReopenedAt(), loadMessages(ticket.getTicketId()));
    }

    @Transactional(readOnly = true)
    public MerchantDisputeDetailDto getMerchantDetail(Long userId, String ticketId) {
        permissionService.requirePermission(userId, "merchant:disputes:list");
        merchantPortalGuard.requireAccess(userId);
        DisputeTicket ticket = requireTicket(ticketId);
        requireTicketDeviceAccess(userId, ticket);
        DisputeTicketDto dto = toMerchantDto(ticket);
        List<DisputeMessageDto> messages = loadMessages(ticket.getTicketId());
        boolean canReply = "OPEN".equals(ticket.getStatus())
                && permissionService.hasPermission(userId, "merchant:disputes:reply");
        return new MerchantDisputeDetailDto(dto, messages, canReply);
    }

    @Transactional
    public MerchantDisputeDetailDto replyAsMerchant(Long userId, String ticketId,
                                                    MerchantReplyDisputeRequest request) {
        permissionService.requirePermission(userId, "merchant:disputes:reply");
        merchantPortalGuard.requireAccess(userId);
        DisputeTicket ticket = requireTicket(ticketId);
        requireTicketDeviceAccess(userId, ticket);
        if (!"OPEN".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待处理工单可回复");
        }
        String body = request != null && request.body() != null ? request.body().trim() : "";
        if (body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复内容不能为空");
        }
        if (body.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回复内容不能超过 1024 字");
        }
        DisputeMessage message = new DisputeMessage();
        message.setTicketId(ticket.getTicketId());
        message.setAuthorType("MERCHANT");
        message.setAuthorId(userId);
        message.setBody(body);
        disputeMessageRepository.save(message);
        auditService.record(userId, "MERCHANT_DISPUTE_REPLY", "DISPUTE", ticket.getTicketId(), body);
        return getMerchantDetail(userId, ticketId);
    }

    private DisputeTicketDto toMerchantDto(DisputeTicket ticket) {
        List<OrderLineDto> suggested = enrichLines(parseItems(ticket.getItems()));
        List<OrderLineDto> resolved = enrichLines(parseItems(ticket.getResolutionItems()));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        String deviceId = session != null ? session.getDeviceId() : null;
        String sessionState = session != null ? session.getState().name() : null;
        String orderId = session != null ? session.getOrderId() : null;
        Integer billedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .filter(o -> !"REFUNDED".equals(o.getStatus()))
                .map(CabinetOrder::getTotalAmountCents)
                .orElse(null);
        Instant now = Instant.now();
        boolean slaOverdue = "OPEN".equals(ticket.getStatus())
                && ticket.getSlaDueAt() != null
                && !ticket.getSlaDueAt().isAfter(now);
        Long slaHoursRemaining = null;
        if ("OPEN".equals(ticket.getStatus()) && ticket.getSlaDueAt() != null && !slaOverdue) {
            slaHoursRemaining = ChronoUnit.HOURS.between(now, ticket.getSlaDueAt());
        }
        return new DisputeTicketDto(
                ticket.getTicketId(), ticket.getSessionId(), deviceId, ticket.getReason(),
                ticket.getStatus(), suggested, resolved, ticket.getCreatedAt(), ticket.getResolvedAt(),
                null, null, sessionState, orderId, billedAmountCents,
                ticket.getSlaDueAt(), slaOverdue, slaHoursRemaining,
                ticket.getCategory(), null, null,
                ticket.getClosedAt(), ticket.getReopenedAt(), List.of());
    }

    private DisputeTicket requireTicket(String ticketId) {
        return disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "争议工单不存在"));
    }

    private void requireTicketDeviceAccess(Long userId, DisputeTicket ticket) {
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        if (session == null || session.getDeviceId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND);
        }
        merchantScopeService.requireDeviceAccess(userId, session.getDeviceId());
    }

    private List<DisputeMessageDto> loadMessages(String ticketId) {
        List<DisputeMessage> rows = disputeMessageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = rows.stream()
                .map(DisputeMessage::getAuthorId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, String> names = userInfoRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(
                        com.aicabinet.trade.domain.UserInfo::getUserId,
                        u -> u.getName() != null && !u.getName().isBlank()
                                ? u.getName() : u.getPhoneNumber(),
                        (a, b) -> a));
        return rows.stream()
                .map(m -> new DisputeMessageDto(
                        m.getMessageId(),
                        m.getAuthorType(),
                        m.getAuthorId(),
                        resolveAuthorName(m, names),
                        m.getBody(),
                        m.getCreatedAt()))
                .toList();
    }

    private static String resolveAuthorName(DisputeMessage message, Map<Long, String> names) {
        if (message.getAuthorId() != null && names.containsKey(message.getAuthorId())) {
            return names.get(message.getAuthorId());
        }
        return switch (message.getAuthorType() != null ? message.getAuthorType() : "") {
            case "MERCHANT" -> "商户";
            case "OPERATOR" -> "平台运营";
            case "SYSTEM" -> "系统";
            default -> message.getAuthorType();
        };
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

    private List<OrderLineDto> enrichLines(List<OrderLineDto> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        List<String> skuIds = lines.stream().map(OrderLineDto::skuId).distinct().toList();
        Map<String, String> names = skuCatalogRepository.findAllById(skuIds).stream()
                .collect(Collectors.toMap(
                        com.aicabinet.trade.domain.SkuCatalog::getSkuId,
                        com.aicabinet.trade.domain.SkuCatalog::getSkuName));
        Map<String, Integer> prices = skuCatalogRepository.findAllById(skuIds).stream()
                .collect(Collectors.toMap(
                        com.aicabinet.trade.domain.SkuCatalog::getSkuId,
                        com.aicabinet.trade.domain.SkuCatalog::getPriceCents));
        return lines.stream()
                .map(line -> {
                    int unit = prices.getOrDefault(line.skuId(), 0);
                    int qty = line.quantity();
                    return new OrderLineDto(
                            line.skuId(),
                            names.getOrDefault(line.skuId(), line.skuName()),
                            qty,
                            unit,
                            unit * qty,
                            line.batchNo());
                })
                .toList();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "USER_APPEAL";
        }
        return switch (raw.trim().toUpperCase()) {
            case "USER_APPEAL", "RECOGNITION", "VIDEO_MISSING", "PAYMENT", "INVENTORY", "OTHER" ->
                    raw.trim().toUpperCase();
            default -> "OTHER";
        };
    }

    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NORMAL";
        }
        return switch (raw.trim().toUpperCase()) {
            case "LOW", "NORMAL", "HIGH", "URGENT" -> raw.trim().toUpperCase();
            default -> "NORMAL";
        };
    }

    private static String priorityForRecognition(VisionServiceClient.RecognitionResult recognition) {
        if (recognition == null) {
            return "HIGH";
        }
        if (recognition.items() == null || recognition.items().isEmpty()) {
            return "HIGH";
        }
        return recognition.overallConfidence() < 0.6f ? "HIGH" : "NORMAL";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
