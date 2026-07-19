package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DisputeMessageDto;
import com.aicabinet.common.dto.DisputeTicketDto;
import com.aicabinet.common.dto.FileAttachmentDto;
import com.aicabinet.common.dto.FileDisputeRequest;
import com.aicabinet.common.dto.MerchantDisputeDetailDto;
import com.aicabinet.common.dto.MerchantReplyDisputeRequest;
import com.aicabinet.common.dto.OrderLineDto;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
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
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.mapper.DisputeMessageMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
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

    private final DisputeTicketMapper disputeRepository;
    private final DisputeMessageMapper disputeMessageRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final SettlementService settlementService;
    private final ObjectMapper objectMapper;
    private final MinioVideoService minioVideoService;
    private final AdminAuditService auditService;
    private final RiskControlService riskControlService;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final SkuCatalogMapper skuCatalogRepository;
    private final DisputeSlaProperties disputeSlaProperties;
    private final UserInfoMapper userInfoRepository;
    private final OpsExceptionService opsExceptionService;
    private final FileAttachmentService fileAttachmentService;
    private final RefundPolicyService refundPolicyService;

    public DisputeService(DisputeTicketMapper disputeRepository,
                          DisputeMessageMapper disputeMessageRepository,
                          ShoppingSessionMapper sessionRepository,
                          CabinetOrderMapper orderRepository,
                          SettlementService settlementService,
                          ObjectMapper objectMapper,
                          MinioVideoService minioVideoService,
                          AdminAuditService auditService,
                          RiskControlService riskControlService,
                          PermissionService permissionService,
                          MerchantScopeService merchantScopeService,
                          MerchantPortalGuard merchantPortalGuard,
                          SkuCatalogMapper skuCatalogRepository,
                          DisputeSlaProperties disputeSlaProperties,
                          UserInfoMapper userInfoRepository,
                          @Lazy OpsExceptionService opsExceptionService,
                          FileAttachmentService fileAttachmentService,
                          RefundPolicyService refundPolicyService) {
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
        this.opsExceptionService = opsExceptionService;
        this.fileAttachmentService = fileAttachmentService;
        this.refundPolicyService = refundPolicyService;
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
        DisputeTicketDto dto = saveOpenTicket(userId, session.getSessionId(), request.reason().trim(), "[]",
                normalizeCategory(request.category()), normalizePriority(request.priority()));
        fileAttachmentService.bindEvidenceToDispute(userId, dto.ticketId(), request.evidenceFileIds());
        session.setState(SessionState.DISPUTED);
        sessionRepository.save(session);
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order -> {
            if ("PAID".equals(order.getStatus()) || "COMPLETED".equals(order.getStatus())) {
                order.setStatus("DISPUTED");
                orderRepository.save(order);
            }
        });
        return toDto(disputeRepository.findById(dto.ticketId()).orElseThrow());
    }

    /**
     * 消费者自助全额退款：创建申诉工单（可带附图）并立即原路退款。
     */
    @Transactional
    public OrderRefundResultDto refundByConsumer(Long userId, String orderId, OrderRefundRequest request) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        if (!userId.equals(order.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
        }
        if (refundPolicyService != null && !refundPolicyService.allowsAutoRefund(order.getDeviceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该柜机未开启自助退款，请提交账单申诉，由运营审核后处理");
        }
        return executeFullRefund(userId, order, request, false);
    }

    /**
     * 运营后台直接全额退款（无需先点争议结案）。
     */
    @Transactional
    public OrderRefundResultDto refundByOperator(Long operatorId, String orderId, OrderRefundRequest request) {
        permissionService.requirePermission(operatorId, "ops:order:refund");
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        ShoppingSession session = sessionRepository.findById(order.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        return executeFullRefund(operatorId, order, request, true);
    }

    private OrderRefundResultDto executeFullRefund(Long actorId, CabinetOrder order, OrderRefundRequest request,
                                                   boolean operator) {
        if ("REFUNDED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已退款");
        }
        if (!Set.of("PAID", "COMPLETED", "DISPUTED").contains(String.valueOf(order.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可退款");
        }
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写至少 4 字退款原因");
        }
        ShoppingSession session = sessionRepository.findById(order.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        DisputeTicket ticket = disputeRepository.findBySessionId(session.getSessionId()).orElse(null);
        if (ticket == null) {
            DisputeTicketDto created = saveOpenTicket(
                    order.getUserId(),
                    session.getSessionId(),
                    reason,
                    "[]",
                    "USER_APPEAL",
                    "NORMAL");
            ticket = disputeRepository.findById(created.ticketId()).orElseThrow();
        } else if ("RESOLVED".equals(ticket.getStatus()) || "CLOSED".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "关联争议已结案，无法再次退款");
        } else {
            ticket.setReason(reason);
            ticket.setCategory("USER_APPEAL");
            disputeRepository.save(ticket);
        }
        Long evidenceOwner = operator ? order.getUserId() : actorId;
        if (!operator) {
            fileAttachmentService.bindEvidenceToDispute(evidenceOwner, ticket.getTicketId(),
                    request != null ? request.evidenceFileIds() : null);
        } else if (request != null && request.evidenceFileIds() != null && !request.evidenceFileIds().isEmpty()) {
            // 运营代传附图时仍校验上传者归属（通常是消费者先传）
            fileAttachmentService.bindEvidenceToDispute(order.getUserId(), ticket.getTicketId(),
                    request.evidenceFileIds());
        }
        int refunded = settlementService.waiveAndRefund(session);
        ticket.setStatus("RESOLVED");
        ticket.setResolutionItems("[]");
        ticket.setResolvedAt(Instant.now());
        if (operator) {
            ticket.setOperatorNote("运营直接退款: " + reason);
        }
        disputeRepository.save(ticket);
        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        auditService.record(actorId, operator ? "ORDER_REFUND_OPS" : "ORDER_REFUND_CONSUMER",
                "ORDER", order.getOrderId(),
                "ticket=" + ticket.getTicketId() + "; refund=" + refunded + "; reason=" + reason);
        String message = refunded > 0
                ? "退款成功，已退回 ¥" + String.format("%.2f", refunded / 100.0)
                : "已处理，本单无需退款金额";
        return new OrderRefundResultDto(
                order.getOrderId(),
                session.getSessionId(),
                ticket.getTicketId(),
                "REFUNDED",
                refunded,
                order.getPayChannel(),
                message);
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
        permissionService.requirePermission(operatorId, "ops:dispute:resolve");
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

        opsExceptionService.resolveOpenForSession(operatorId, session.getSessionId(),
                "争议结案(" + resolutionType + ")同步关闭异常");

        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        return result;
    }

    /**
     * Marks an open dispute ticket resolved when settlement was already applied elsewhere
     * (for example ops exception manual-resolve). Idempotent when no open ticket exists.
     */
    @Transactional
    public void closeOpenTicketForSession(Long operatorId, String sessionId, String resolutionType,
                                          List<ResolveDisputeRequest.ManualLineItem> lines) {
        disputeRepository.findBySessionId(sessionId).ifPresent(ticket -> {
            if (!"OPEN".equals(ticket.getStatus())) {
                return;
            }
            String type = normalizeResolutionType(resolutionType);
            ticket.setStatus("RESOLVED");
            ticket.setResolvedAt(Instant.now());
            if ("WAIVE".equals(type)) {
                ticket.setResolutionItems("[]");
            } else if ("KEEP".equals(type)) {
                ticket.setResolutionItems(ticket.getItems() != null ? ticket.getItems() : "[]");
            } else {
                var manualItems = (lines == null ? List.<ResolveDisputeRequest.ManualLineItem>of() : lines).stream()
                        .filter(line -> line.quantity() > 0)
                        .map(line -> new VisionServiceClient.RecognizedItem(line.skuId(), line.quantity(), 1.0f))
                        .toList();
                ticket.setResolutionItems(toJson(manualItems));
            }
            disputeRepository.save(ticket);
            auditService.record(operatorId, "DISPUTE_SYNC_FROM_OPS_EXCEPTION", "DISPUTE", ticket.getTicketId(),
                    "session=" + sessionId + "; type=" + type);
        });
    }

    @Transactional
    public DisputeTicketDto closeTicket(Long operatorId, String ticketId, CloseDisputeRequest request) {
        permissionService.requirePermission(operatorId, "ops:dispute:resolve");
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
        permissionService.requirePermission(operatorId, "ops:dispute:resolve");
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
                ticket.getClosedAt(), ticket.getReopenedAt(), loadMessages(ticket.getTicketId()),
                fileAttachmentService.listDisputeEvidence(ticket.getTicketId()));
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
                ticket.getClosedAt(), ticket.getReopenedAt(), List.of(),
                fileAttachmentService.listDisputeEvidence(ticket.getTicketId()));
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
