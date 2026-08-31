package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.DisputeMessageDto;
import com.aicabinet.common.dto.DisputeTicketDto;
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
import com.aicabinet.trade.util.BizIds;
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
import java.util.stream.Collectors;

@Service
public class DisputeService {
    private static final String PERM_OPS_DISPUTE_RESOLVE = "ops:dispute:resolve";
    private static final String PARTIAL_REFUNDED = "PARTIAL_REFUNDED";
    private static final String RECOGNITION = "RECOGNITION";
    private static final String USER_APPEAL = "USER_APPEAL";
    private static final String PERM_OPS_DISPUTE = "ops:dispute";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String SESSION = "session=";
    private static final String CONFIRM = "CONFIRM";
    private static final String DISPUTE = "DISPUTE";
    private static final String ADJUST = "ADJUST";
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String WAIVE = "WAIVE";


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
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final SkuCatalogMapper skuCatalogRepository;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DisputeService self;
    private final DisputeSlaProperties disputeSlaProperties;
    private final SystemConfigService systemConfigService;
    private final UserInfoMapper userInfoRepository;
    private final OpsExceptionService opsExceptionService;
    private final FileAttachmentService fileAttachmentService;
    private final RefundPolicyService refundPolicyService;
    private final VideoArchiveService videoArchiveService;
    private final OrderPaymentService orderPaymentService;
    private final DistributedLockService distributedLockService;

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
                          MerchantFeaturePackService merchantFeaturePackService,
                          MerchantPortalGuard merchantPortalGuard,
                          SkuCatalogMapper skuCatalogRepository,
                          DisputeSlaProperties disputeSlaProperties,
                          UserInfoMapper userInfoRepository,
                          @Lazy OpsExceptionService opsExceptionService,
                          FileAttachmentService fileAttachmentService,
                          RefundPolicyService refundPolicyService,
                          VideoArchiveService videoArchiveService,
                          OrderPaymentService orderPaymentService,
                          DistributedLockService distributedLockService,
                          SystemConfigService systemConfigService,
                          @Lazy DisputeService self) {
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
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.skuCatalogRepository = skuCatalogRepository;
        this.disputeSlaProperties = disputeSlaProperties;
        this.userInfoRepository = userInfoRepository;
        this.opsExceptionService = opsExceptionService;
        this.fileAttachmentService = fileAttachmentService;
        this.refundPolicyService = refundPolicyService;
        this.videoArchiveService = videoArchiveService;
        this.orderPaymentService = orderPaymentService;
        this.distributedLockService = distributedLockService;
        this.systemConfigService = systemConfigService;
        this.self = self;
    }

    @Transactional
    public DisputeTicketDto createTicket(ShoppingSession session,
                                         VisionServiceClient.RecognitionResult recognition,
                                         String reason) {
        var existing = disputeRepository.findBySessionId(session.getSessionId());
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        DisputeTicketDto dto = saveOpenTicket(new OpenTicketDraft(
                session.getUserId(),
                session.getSessionId(),
                reason,
                toJson(recognition != null && recognition.items() != null ? recognition.items() : List.of()),
                RECOGNITION,
                recognition != null ? priorityForRecognition(recognition) : "HIGH",
                recognition != null ? reviewCodeFor(recognition, reason) : "TIMEOUT",
                toJson(recognition != null && recognition.detectedClasses() != null
                        ? recognition.detectedClasses() : List.of())));
        // 争议/回查会话立即归档录像副本（原始录像会在保留期后过期）
        videoArchiveService.archiveSession(session);
        return dto;
    }

    /** 会话卡在上传/识别/结算：无视觉结果时开争议单，避免静默扣款。 */
    @Transactional
    public DisputeTicketDto createTimeoutTicket(ShoppingSession session, String reason) {
        String safeReason = reason != null && !reason.isBlank()
                ? reason
                : "识别超时，已转人工审核，本次暂未扣款";
        return self.createTicket(session,
                new VisionServiceClient.RecognitionResult(
                        session.getRecognitionTaskId(),
                        List.of(),
                        0f,
                        true,
                        "timeout",
                        List.of()),
                safeReason);
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
        var existing = disputeRepository.findBySessionId(session.getSessionId());
        if (existing.isPresent()) {
            String st = existing.get().getStatus();
            if (STATUS_RESOLVED.equalsIgnoreCase(st) || STATUS_CLOSED.equalsIgnoreCase(st)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DISPUTE_APPEAL_CLOSED);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.DISPUTE_ALREADY_EXISTS);
        }
        DisputeTicketDto dto = saveOpenTicket(new OpenTicketDraft(
                userId, session.getSessionId(), request.reason().trim(), "[]",
                normalizeCategory(request.category()), normalizePriority(request.priority()), null, null));
        // 用户事后申诉：在原始录像仍保留期间立即归档，避免过期后无法回放
        videoArchiveService.archiveSession(session);
        fileAttachmentService.bindEvidenceToDispute(userId, dto.ticketId(), request.evidenceFileIds());
        session.setState(SessionState.DISPUTED);
        sessionRepository.save(session);
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order -> {
            if ("PAID".equals(order.getStatus()) || STATUS_COMPLETED.equals(order.getStatus())) {
                order.setStatus(CabinetConstants.ORDER_STATUS_DISPUTED);
                orderRepository.save(order);
            }
        });
        return toDto(disputeRepository.findById(dto.ticketId()).orElseThrow());
    }

    /**
     * 运营后台退款（全额或按行部分退）。
     */
    @Transactional
    public OrderRefundResultDto refundByOperator(Long operatorId, String orderId, OrderRefundRequest request) {
        permissionService.requirePermission(operatorId, "ops:order:refund");
        return runWithOrderPaymentLock(orderId, lockedOrder -> {
            ShoppingSession session = sessionRepository.findById(lockedOrder.getSessionId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
            merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
            if (request != null && request.lines() != null && !request.lines().isEmpty()) {
                return executePartialRefund(operatorId, lockedOrder, request, true);
            }
            return executeFullRefund(operatorId, lockedOrder, request, true);
        });
    }

    /**
     * 消费者自助退款：全额或按行（受限额策略约束）。
     */
    @Transactional
    public OrderRefundResultDto refundByConsumer(Long userId, String orderId, OrderRefundRequest request) {
        return runWithOrderPaymentLock(orderId, lockedOrder -> {
            if (!userId.equals(lockedOrder.getUserId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND);
            }
            boolean partial = request != null && request.lines() != null && !request.lines().isEmpty();
            if (partial) {
                int estimate = settlementService.estimatePartialRefundCents(lockedOrder, request.lines());
                refundPolicyService.assertConsumerSelfRefundAllowed(lockedOrder, estimate, true);
                return executePartialRefund(userId, lockedOrder, request, false);
            }
            refundPolicyService.assertConsumerSelfRefundAllowed(lockedOrder, lockedOrder.getTotalAmountCents(), false);
            return executeFullRefund(userId, lockedOrder, request, false);
        });
    }

    private OrderRefundResultDto executePartialRefund(Long actorId, CabinetOrder order, OrderRefundRequest request,
                                                      boolean operator) {
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已退款");
        }
        if (!Set.of("PAID", STATUS_COMPLETED, CabinetConstants.ORDER_STATUS_DISPUTED, PARTIAL_REFUNDED).contains(String.valueOf(order.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可退款");
        }
        String reason = request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写至少 4 字退款原因");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定退款明细行");
        }
        boolean defaultRestore = RefundInventoryPolicy.resolve(
                request.restoreInventory(), reason, !operator);
        var outcome = settlementService.partialRefund(order, request.lines(), defaultRestore, reason);
        ShoppingSession session = sessionRepository.findById(order.getSessionId()).orElse(null);
        if (session != null && CabinetConstants.ORDER_STATUS_REFUNDED.equals(outcome.status())) {
            session.setState(SessionState.COMPLETED);
            sessionRepository.save(session);
        }
        auditService.appendLog(actorId,
                operator ? "ORDER_PARTIAL_REFUND_OPS" : "ORDER_PARTIAL_REFUND_CONSUMER",
                "ORDER", order.getOrderId(),
                "refund=" + outcome.refundedCents() + "; status=" + outcome.status()
                        + "; restore=" + outcome.anyInventoryRestored() + "; reason=" + reason);
        String hint = outcome.anyInventoryRestored() ? "（含回库行）" : "（未回库/仅退款）";
        String message = (outcome.status().equals(CabinetConstants.ORDER_STATUS_REFUNDED) ? "退款成功" : "部分退款成功")
                + "，已退回 ¥" + String.format("%.2f", outcome.refundedCents() / 100.0)
                + "，状态 " + outcome.status() + hint;
        return new OrderRefundResultDto(
                order.getOrderId(),
                order.getSessionId(),
                null,
                outcome.status(),
                outcome.refundedCents(),
                order.getPayChannel(),
                message,
                outcome.anyInventoryRestored(),
                true);
    }

    private OrderRefundResultDto executeFullRefund(Long actorId, CabinetOrder order, OrderRefundRequest request,
                                                   boolean operator) {
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已退款");
        }
        if (!Set.of("PAID", STATUS_COMPLETED, CabinetConstants.ORDER_STATUS_DISPUTED, PARTIAL_REFUNDED).contains(String.valueOf(order.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可退款");
        }
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写至少 4 字退款原因");
        }
        ShoppingSession session = sessionRepository.findById(order.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        DisputeTicket ticket = ensureDisputeTicketForFullRefund(order, session, reason);
        bindFullRefundEvidence(actorId, order, ticket, request, operator);
        boolean restoreInventory = RefundInventoryPolicy.resolve(
                request != null ? request.restoreInventory() : null,
                reason,
                !operator);
        int refunded = settlementService.waiveAndRefund(session, restoreInventory);
        finalizeFullRefundTicket(ticket, operator, reason, restoreInventory);
        disputeRepository.save(ticket);
        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        auditService.appendLog(actorId, operator ? "ORDER_REFUND_OPS" : "ORDER_REFUND_CONSUMER",
                "ORDER", order.getOrderId(),
                "ticket=" + ticket.getTicketId() + "; refund=" + refunded
                        + "; restoreInventory=" + restoreInventory + "; reason=" + reason);
        String inventoryHint = restoreInventory ? "，库存已回库" : "，库存未回库（货已离柜/仅退款）";
        String message = refunded > 0
                ? "退款成功，已退回 ¥" + String.format("%.2f", refunded / 100.0) + inventoryHint
                : "已处理，本单无需退款金额" + inventoryHint;
        return new OrderRefundResultDto(
                order.getOrderId(),
                session.getSessionId(),
                ticket.getTicketId(),
                CabinetConstants.ORDER_STATUS_REFUNDED,
                refunded,
                order.getPayChannel(),
                message,
                restoreInventory,
                false);
    }

    private DisputeTicket ensureDisputeTicketForFullRefund(CabinetOrder order, ShoppingSession session, String reason) {
        DisputeTicket ticket = disputeRepository.findBySessionId(session.getSessionId()).orElse(null);
        if (ticket == null) {
            DisputeTicketDto created = saveOpenTicket(new OpenTicketDraft(
                    order.getUserId(),
                    session.getSessionId(),
                    reason,
                    "[]",
                    USER_APPEAL,
                    STATUS_NORMAL,
                    null,
                    null));
            return disputeRepository.findById(created.ticketId()).orElseThrow();
        }
        if (STATUS_RESOLVED.equals(ticket.getStatus()) || STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "关联争议已结案，无法再次退款");
        }
        ticket.setReason(reason);
        ticket.setCategory(USER_APPEAL);
        return ticket;
    }

    private void bindFullRefundEvidence(Long actorId, CabinetOrder order, DisputeTicket ticket,
                                        OrderRefundRequest request, boolean operator) {
        if (!operator) {
            fileAttachmentService.bindEvidenceToDispute(actorId, ticket.getTicketId(),
                    request != null ? request.evidenceFileIds() : null);
            return;
        }
        if (request != null && request.evidenceFileIds() != null && !request.evidenceFileIds().isEmpty()) {
            fileAttachmentService.bindEvidenceToDispute(order.getUserId(), ticket.getTicketId(),
                    request.evidenceFileIds());
        }
    }

    private static void finalizeFullRefundTicket(DisputeTicket ticket, boolean operator,
                                                 String reason, boolean restoreInventory) {
        ticket.setStatus(STATUS_RESOLVED);
        ticket.setResolutionItems("[]");
        ticket.setResolvedAt(Instant.now());
        if (operator) {
            ticket.setOperatorNote("运营直接退款: " + reason
                    + (restoreInventory ? " [回库]" : " [不回库]"));
        }
    }

    private record OpenTicketDraft(
            Long userId, String sessionId, String reason, String itemsJson,
            String category, String priority, String reviewCode, String detectedClassesJson) {}

    private DisputeTicketDto saveOpenTicket(OpenTicketDraft draft) {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId(newTicketId());
        ticket.setSessionId(draft.sessionId());
        ticket.setReason(draft.reason());
        ticket.setStatus("OPEN");
        ticket.setCategory(draft.category());
        ticket.setPriority(draft.priority());
        ticket.setItems(draft.itemsJson());
        if (draft.reviewCode() != null && !draft.reviewCode().isBlank()) {
            ticket.setReviewCode(draft.reviewCode().trim().toUpperCase());
        }
        if (draft.detectedClassesJson() != null && !draft.detectedClassesJson().isBlank()
                && !"null".equals(draft.detectedClassesJson())) {
            ticket.setDetectedClasses(draft.detectedClassesJson());
        }
        Instant now = Instant.now();
        ticket.setSlaDueAt(now.plus(
                systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_HOURS, disputeSlaProperties.hours()),
                ChronoUnit.HOURS));
        disputeRepository.save(ticket);
        riskControlService.onDisputeCreated(draft.userId(), draft.sessionId());
        return toDto(ticket);
    }

    private static String newTicketId() {
        return BizIds.nextNumeric();
    }

    @Transactional(readOnly = true)
    public List<DisputeTicketDto> listMyTickets(Long userId) {
        return disputeRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::toDto).toList();
    }

    /**
     * 消费者按 ticketId 或 sessionId 查询本人争议单；非本人返回 404，避免枚举泄露。
     */
    @Transactional(readOnly = true)
    public DisputeTicketDto getMyTicket(Long userId, String ticketId, String sessionId) {
        DisputeTicket ticket = null;
        if (ticketId != null && !ticketId.isBlank()) {
            ticket = disputeRepository.findById(ticketId.trim()).orElse(null);
        }
        if (ticket == null && sessionId != null && !sessionId.isBlank()) {
            ticket = disputeRepository.findBySessionId(sessionId.trim()).orElse(null);
        }
        if (ticket == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND);
        }
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        if (session == null || session.getUserId() == null || !session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND);
        }
        return toDto(ticket);
    }

    @Transactional(readOnly = true)
    public List<DisputeTicketDto> listOpenTickets(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DISPUTE);
        return disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN")
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<DisputeTicketDto> listTickets(Long operatorId, DisputeTicketListQuery query) {
        permissionService.requirePermission(operatorId, PERM_OPS_DISPUTE);
        Pageable pageable = PageRequest.of(query.page(), Math.min(query.size(), 100));
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(operatorId, query.deviceId());
        Page<DisputeTicket> result;
        if (deviceScope != null && deviceScope.isEmpty()) {
            result = Page.empty(pageable);
        } else if (deviceScope != null) {
            result = disputeRepository.searchByDeviceIds(
                    blankToNull(query.status()), blankToNull(query.sessionId()), deviceScope, blankToNull(query.orderId()),
                    blankToNull(query.category()), blankToNull(query.reviewCode()), pageable);
        } else {
            result = disputeRepository.search(
                    blankToNull(query.status()), blankToNull(query.sessionId()), blankToNull(query.deviceId()),
                    blankToNull(query.orderId()),
                    blankToNull(query.category()), blankToNull(query.reviewCode()), pageable);
        }
        return new PageResult<>(
                result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    public record DisputeTicketListQuery(
            int page, int size, String status, String sessionId, String deviceId,
            String orderId, String category, String reviewCode) {}

    /** 运营按工单号取详情（深链 / 跨页打开）。 */
    @Transactional(readOnly = true)
    public DisputeTicketDto getTicket(Long operatorId, String ticketId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DISPUTE);
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        return toDto(ticket);
    }

    @Transactional(noRollbackFor = {com.aicabinet.trade.service.BalanceInsufficientException.class})
    public ResolveDisputeResultDto resolveTicket(Long operatorId, String ticketId, ResolveDisputeRequest request) {
        permissionService.requirePermission(operatorId, PERM_OPS_DISPUTE_RESOLVE);
        return runWithDisputeTicketLock(ticketId, () -> doResolveTicket(operatorId, ticketId, request));
    }

    private ResolveDisputeResultDto doResolveTicket(Long operatorId, String ticketId, ResolveDisputeRequest request) {
        DisputeTicket ticket = disputeRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        if (!"OPEN".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.TICKET_ALREADY_RESOLVED);
        }
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());

        ResolveDisputeRequest body = requireResolveRequest(request);
        String resolutionType = requireResolutionType(body.effectiveResolutionType());
        if ((ADJUST.equals(resolutionType) || CONFIRM.equals(resolutionType))
                && (body.items() == null || body.items().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_ITEMS_REQUIRED);
        }
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order -> {
            if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus()) && !WAIVE.equals(resolutionType) && !"KEEP".equals(resolutionType)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_REFUNDED);
            }
        });
        ResolveDisputeResultDto result = switch (resolutionType) {
            case "KEEP" -> resolveKeep(operatorId, ticket, session);
            case WAIVE -> resolveWaive(operatorId, ticket, session, body.restoreInventory());
            case ADJUST, CONFIRM -> resolveConfirm(operatorId, ticket, session, body, resolutionType);
            default -> throw new IllegalStateException("unexpected resolution: " + resolutionType); // NOSONAR java:S2583
        };

        opsExceptionService.resolveOpenForSession(operatorId, session.getSessionId(),
                "争议结案(" + resolutionType + ")同步关闭异常");

        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        // 三端一致：结案后订单不得再挂 DISPUTED
        // WAIVE → REFUNDED（免单兜底）；KEEP/CONFIRM/ADJUST → PAID
        final String resolvedType = resolutionType;
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order ->
                alignOrderStatusAfterDisputeResolve(order, resolvedType));
        return result;
    }

    /** 结案后订单状态与真实入账对齐，避免未支付却被标 PAID。 */
    private void alignOrderStatusAfterDisputeResolve(CabinetOrder order, String resolutionType) {
        if (!CabinetConstants.ORDER_STATUS_DISPUTED.equals(order.getStatus())) {
            return;
        }
        if (WAIVE.equals(resolutionType)) {
            order.setStatus(CabinetConstants.ORDER_STATUS_REFUNDED);
            if (order.getRefundedAt() == null) {
                order.setRefundedAt(Instant.now());
            }
            orderRepository.save(order);
            return;
        }
        int netPaid = orderPaymentService.netCompletedCents(order.getOrderId());
        if (netPaid <= 0) {
            order.setStatus(order.getTotalAmountCents() <= 0 ? "PAID" : "PENDING");
        } else if (order.getRefundedCents() > 0 && netPaid < order.getTotalAmountCents()) {
            order.setStatus(PARTIAL_REFUNDED);
        } else {
            order.setStatus("PAID");
        }
        orderRepository.save(order);
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
            ticket.setStatus(STATUS_RESOLVED);
            ticket.setResolvedAt(Instant.now());
            applyAssignee(ticket, operatorId);
            if (WAIVE.equals(type)) {
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
            auditService.appendLog(operatorId, "DISPUTE_SYNC_FROM_OPS_EXCEPTION", DISPUTE, ticket.getTicketId(),
                    SESSION + sessionId + "; type=" + type);
        });
    }

    @Transactional
    public DisputeTicketDto closeTicket(Long operatorId, String ticketId, CloseDisputeRequest request) {
        permissionService.requirePermission(operatorId, PERM_OPS_DISPUTE_RESOLVE);
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        if (!STATUS_RESOLVED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only resolved disputes can be closed");
        }
        ticket.setStatus(STATUS_CLOSED);
        ticket.setClosedAt(Instant.now());
        ticket.setOperatorNote(trimToNull(request != null ? request.note() : null));
        disputeRepository.save(ticket);
        auditService.appendLog(operatorId, "DISPUTE_CLOSE", DISPUTE, ticketId,
                SESSION + ticket.getSessionId());
        return toDto(ticket);
    }

    @Transactional
    public DisputeTicketDto reopenTicket(Long operatorId, String ticketId, ReopenDisputeRequest request) {
        permissionService.requirePermission(operatorId, PERM_OPS_DISPUTE_RESOLVE);
        DisputeTicket ticket = disputeRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, session.getDeviceId());
        if ("OPEN".equals(ticket.getStatus())) {
            return toDto(ticket);
        }
        if (!STATUS_RESOLVED.equals(ticket.getStatus()) && !STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.TICKET_ALREADY_RESOLVED);
        }
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order -> {
            if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "已退款订单不可重新打开争议，避免重复退款");
            }
        });
        ticket.setStatus("OPEN");
        ticket.setPriority(normalizePriority(request != null ? request.priority() : ticket.getPriority()));
        ticket.setOperatorNote(trimToNull(request != null ? request.note() : null));
        ticket.setClosedAt(null);
        ticket.setReopenedAt(Instant.now());
        if (ticket.getSlaDueAt() == null || !ticket.getSlaDueAt().isAfter(Instant.now())) {
        ticket.setSlaDueAt(Instant.now().plus(
                systemConfigService.getInt(SystemConfigService.DISPUTE_SLA_HOURS, disputeSlaProperties.hours()),
                ChronoUnit.HOURS));
        }
        session.setState(SessionState.DISPUTED);
        sessionRepository.save(session);
        // 三端一致：重开争议时订单也应回到 DISPUTED（与消费者申诉 fileByConsumer 对齐）
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order -> {
            if ("PAID".equals(order.getStatus()) || STATUS_COMPLETED.equals(order.getStatus())) {
                order.setStatus(CabinetConstants.ORDER_STATUS_DISPUTED);
                orderRepository.save(order);
            }
        });
        disputeRepository.save(ticket);
        auditService.appendLog(operatorId, "DISPUTE_REOPEN", DISPUTE, ticketId,
                SESSION + ticket.getSessionId());
        return toDto(ticket);
    }

    private ResolveDisputeResultDto resolveWaive(Long operatorId, DisputeTicket ticket, ShoppingSession session,
                                                 Boolean restoreInventoryFlag) {
        boolean restore = RefundInventoryPolicy.resolve(restoreInventoryFlag, ticket.getReason(), true);
        int refunded = settlementService.waiveAndRefund(session, restore);
        ticket.setStatus(STATUS_RESOLVED);
        ticket.setResolutionItems("[]");
        ticket.setResolvedAt(Instant.now());
        applyAssignee(ticket, operatorId);
        disputeRepository.save(ticket);
        auditService.appendLog(operatorId, "DISPUTE_WAIVE", DISPUTE, ticket.getTicketId(),
                "refund=" + refunded + "; restoreInventory=" + restore);
        String message;
        if (refunded > 0) {
            message = "已免单，退还 ¥" + String.format("%.2f", refunded / 100.0)
                    + (restore ? "，库存已回库" : "，库存未回库");
        } else {
            message = "已免单，无需扣款";
        }
        return new ResolveDisputeResultDto(null, WAIVE, refunded, 0, -refunded, message);
    }

    private ResolveDisputeResultDto resolveKeep(Long operatorId, DisputeTicket ticket, ShoppingSession session) {
        int originalAmount = orderRepository.findBySessionId(session.getSessionId())
                .filter(order -> !CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus()))
                .map(CabinetOrder::getTotalAmountCents)
                .orElse(0);
        ticket.setStatus(STATUS_RESOLVED);
        ticket.setResolutionItems(ticket.getItems() != null ? ticket.getItems() : "[]");
        ticket.setResolvedAt(Instant.now());
        applyAssignee(ticket, operatorId);
        disputeRepository.save(ticket);
        auditService.appendLog(operatorId, "DISPUTE_KEEP_BILL", DISPUTE, ticket.getTicketId(),
                SESSION + ticket.getSessionId() + " amount=" + originalAmount);
        return new ResolveDisputeResultDto(
                null,
                "KEEP", originalAmount, originalAmount, 0, "已复核，维持原账单");
    }

    private ResolveDisputeResultDto resolveConfirm(Long operatorId,
                                                 DisputeTicket ticket,
                                                 ShoppingSession session,
                                                 ResolveDisputeRequest request,
                                                 String resolutionType) {
        var lines = request.items() == null ? List.<ResolveDisputeRequest.ManualLineItem>of() : request.items();
        var manualItems = lines.stream()
                .filter(i -> i != null && i.quantity() > 0)
                .map(i -> new VisionServiceClient.RecognizedItem(i.skuId(), i.quantity(), 1.0f))
                .toList();
        if (manualItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_ITEMS_REQUIRED);
        }
        SettlementService.ConfirmDisputeResult settled =
                settlementService.confirmDisputedItems(session, manualItems);

        ticket.setStatus(STATUS_RESOLVED);
        ticket.setResolutionItems(toJson(manualItems));
        ticket.setResolvedAt(Instant.now());
        applyAssignee(ticket, operatorId);
        disputeRepository.save(ticket);

        session.setOrderId(settled.order().orderId());
        String message = buildAdjustMessage(settled);
        auditService.appendLog(operatorId, "DISPUTE_RESOLVE", DISPUTE, ticket.getTicketId(),
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

    /** 结案类型必填；兼容历史脚本把字段写成 action（由 DTO @JsonAlias 映射）。 */
    private static ResolveDisputeRequest requireResolveRequest(ResolveDisputeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        return request;
    }

    private static String requireResolutionType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_RESOLUTION_TYPE_REQUIRED);
        }
        String type = normalizeResolutionType(raw);
        if (!List.of("KEEP", WAIVE, CONFIRM, ADJUST).contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_RESOLUTION_TYPE_REQUIRED);
        }
        return type;
    }

    private static String normalizeResolutionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw.trim().toUpperCase()) {
            case "KEEP", "REJECT", "KEEP_BILL" -> "KEEP";
            case WAIVE, "FREE", "REFUND_ALL" -> WAIVE;
            case ADJUST, "补差", "退差" -> ADJUST;
            case CONFIRM, "CHARGE" -> CONFIRM;
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
                .map(DisputeService::resolveBilledAmountCents)
                .orElse(null);
        Integer refundedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .map(DisputeService::resolveRefundedAmountCents)
                .orElse(null);
        Integer claimedAmountCents = sumLineAmountCents(suggested);
        String previewUrl = minioVideoService.presignPlaybackUrl(videoUri).orElse(null);
        Instant now = Instant.now();
        boolean slaOverdue = "OPEN".equals(ticket.getStatus())
                && ticket.getSlaDueAt() != null
                && !ticket.getSlaDueAt().isAfter(now);
        Long slaHoursRemaining = null;
        if ("OPEN".equals(ticket.getStatus()) && ticket.getSlaDueAt() != null && !slaOverdue) {
            slaHoursRemaining = ChronoUnit.HOURS.between(now, ticket.getSlaDueAt());
        }
        String reviewCode = resolveReviewCode(ticket);
        String displayReason = com.aicabinet.trade.support.MerchantNameSupport.disputeReason(
                reviewCode, ticket.getReason());
        return new DisputeTicketDto(
                ticket.getTicketId(), ticket.getSessionId(), deviceId, displayReason,
                ticket.getStatus(), suggested, resolved, ticket.getCreatedAt(), ticket.getResolvedAt(),
                videoUri, previewUrl, sessionState, orderId, billedAmountCents,
                ticket.getSlaDueAt(), slaOverdue, slaHoursRemaining,
                ticket.getCategory(), ticket.getPriority(), ticket.getOperatorNote(),
                ticket.getClosedAt(), ticket.getReopenedAt(), loadMessages(ticket.getTicketId()),
                fileAttachmentService.listDisputeEvidence(ticket.getTicketId()),
                reviewCode,
                parseDetectedClasses(ticket.getDetectedClasses()),
                refundedAmountCents,
                claimedAmountCents,
                session != null ? session.getDeviceName() : null,
                ticket.getAssignee());
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
        boolean canResolve = "OPEN".equals(ticket.getStatus())
                && permissionService.hasPermission(userId, "merchant:disputes:resolve");
        return new MerchantDisputeDetailDto(dto, messages, canReply, canResolve);
    }

    /**
     * 商户有限结案：仅 KEEP / WAIVE / CONFIRM（不可 ADJUST 随意改价）。
     */
    @Transactional(noRollbackFor = {com.aicabinet.trade.service.BalanceInsufficientException.class})
    public ResolveDisputeResultDto resolveAsMerchant(Long userId, String ticketId, ResolveDisputeRequest request) {
        permissionService.requirePermission(userId, "merchant:disputes:resolve");
        merchantPortalGuard.requireAccess(userId);
        return runWithDisputeTicketLock(ticketId, () -> doResolveAsMerchant(userId, ticketId, request));
    }

    private ResolveDisputeResultDto doResolveAsMerchant(Long userId, String ticketId, ResolveDisputeRequest request) {
        DisputeTicket ticket = disputeRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.TICKET_NOT_FOUND));
        requireTicketDeviceAccess(userId, ticket);
        if (!"OPEN".equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.TICKET_ALREADY_RESOLVED);
        }
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));

        ResolveDisputeRequest body = requireResolveRequest(request);
        String resolutionType = requireResolutionType(body.effectiveResolutionType());
        if (!Set.of("KEEP", WAIVE, CONFIRM).contains(resolutionType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户结案仅支持：维持原单 / 免单退款 / 按确认清单");
        }
        if (CONFIRM.equals(resolutionType) && (body.items() == null || body.items().isEmpty())) {
            // 默认用工单建议行，避免商户端无商品选择器时无法结案
            List<ResolveDisputeRequest.ManualLineItem> suggested = parseItems(ticket.getItems()).stream()
                    .filter(i -> i != null && i.skuId() != null && i.quantity() > 0)
                    .map(i -> new ResolveDisputeRequest.ManualLineItem(i.skuId(), i.quantity()))
                    .toList();
            if (suggested.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DISPUTE_ITEMS_REQUIRED);
            }
            body = new ResolveDisputeRequest(
                    suggested, CONFIRM, null, body.restoreInventory());
        }
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order -> {
            if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus()) && !WAIVE.equals(resolutionType) && !"KEEP".equals(resolutionType)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.ORDER_ALREADY_REFUNDED);
            }
        });
        ResolveDisputeResultDto result = switch (resolutionType) {
            case "KEEP" -> resolveKeep(userId, ticket, session);
            case WAIVE -> resolveWaive(userId, ticket, session, body.restoreInventory());
            case CONFIRM -> resolveConfirm(userId, ticket, session, body, CONFIRM);
            default -> throw new IllegalStateException("unexpected resolution: " + resolutionType); // NOSONAR java:S2583
        };
        opsExceptionService.resolveOpenForSession(userId, session.getSessionId(),
                "商户争议结案(" + resolutionType + ")同步关闭异常");
        session.setState(SessionState.COMPLETED);
        sessionRepository.save(session);
        final String resolvedType = resolutionType;
        orderRepository.findBySessionId(session.getSessionId()).ifPresent(order ->
                alignOrderStatusAfterDisputeResolve(order, resolvedType));
        auditService.appendLog(userId, "MERCHANT_DISPUTE_RESOLVE", DISPUTE, ticketId,
                "type=" + resolutionType);
        return result;
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
        auditService.appendLog(userId, "MERCHANT_DISPUTE_REPLY", DISPUTE, ticket.getTicketId(), body);
        return self.getMerchantDetail(userId, ticketId);
    }

    private DisputeTicketDto toMerchantDto(DisputeTicket ticket) {
        List<OrderLineDto> suggested = enrichLines(parseItems(ticket.getItems()));
        List<OrderLineDto> resolved = enrichLines(parseItems(ticket.getResolutionItems()));
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        String deviceId = session != null ? session.getDeviceId() : null;
        String sessionState = session != null ? session.getState().name() : null;
        String orderId = session != null ? session.getOrderId() : null;
        String videoUri = session != null ? session.getVideoUri() : null;
        String previewUrl = minioVideoService.presignPlaybackUrl(videoUri).orElse(null);
        Integer billedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .map(DisputeService::resolveBilledAmountCents)
                .orElse(null);
        Integer refundedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .map(DisputeService::resolveRefundedAmountCents)
                .orElse(null);
        Integer claimedAmountCents = sumLineAmountCents(suggested);
        Instant now = Instant.now();
        boolean slaOverdue = "OPEN".equals(ticket.getStatus())
                && ticket.getSlaDueAt() != null
                && !ticket.getSlaDueAt().isAfter(now);
        Long slaHoursRemaining = null;
        if ("OPEN".equals(ticket.getStatus()) && ticket.getSlaDueAt() != null && !slaOverdue) {
            slaHoursRemaining = ChronoUnit.HOURS.between(now, ticket.getSlaDueAt());
        }
        String reviewCode = resolveReviewCode(ticket);
        String displayReason = com.aicabinet.trade.support.MerchantNameSupport.disputeReason(
                reviewCode, ticket.getReason());
        return new DisputeTicketDto(
                ticket.getTicketId(), ticket.getSessionId(), deviceId, displayReason,
                ticket.getStatus(), suggested, resolved, ticket.getCreatedAt(), ticket.getResolvedAt(),
                videoUri, previewUrl, sessionState, orderId, billedAmountCents,
                ticket.getSlaDueAt(), slaOverdue, slaHoursRemaining,
                ticket.getCategory(), ticket.getPriority(), ticket.getOperatorNote(),
                ticket.getClosedAt(), ticket.getReopenedAt(), List.of(),
                fileAttachmentService.listDisputeEvidence(ticket.getTicketId()),
                reviewCode,
                parseDetectedClasses(ticket.getDetectedClasses()),
                refundedAmountCents,
                claimedAmountCents,
                session != null ? session.getDeviceName() : null,
                ticket.getAssignee());
    }

    private static Integer resolveBilledAmountCents(CabinetOrder order) {
        if (order == null) {
            return null;
        }
        int original = Math.max(0, order.getOriginalAmountCents());
        if (original > 0) {
            return original;
        }
        int total = Math.max(0, order.getTotalAmountCents());
        int refunded = Math.max(0, order.getRefundedCents());
        if (total <= 0 && refunded > 0) {
            return refunded;
        }
        return total > 0 ? total : null;
    }

    private static Integer resolveRefundedAmountCents(CabinetOrder order) {
        if (order == null) {
            return null;
        }
        int refunded = Math.max(0, order.getRefundedCents());
        if (refunded > 0) {
            return refunded;
        }
        if (CabinetConstants.ORDER_STATUS_REFUNDED.equals(order.getStatus())) {
            int original = Math.max(0, order.getOriginalAmountCents());
            int total = Math.max(0, order.getTotalAmountCents());
            int amount = original > 0 ? original : total;
            return amount > 0 ? amount : null;
        }
        return null;
    }

    private static Integer sumLineAmountCents(List<OrderLineDto> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (OrderLineDto line : lines) {
            if (line == null) {
                continue;
            }
            sum += Math.max(0, line.lineAmountCents());
        }
        return sum > 0 ? sum : null;
    }

    /** 争议建议行金额合计（SKU 现价 × 数量），供商户列表等轻量映射复用。 */
    public Integer resolveClaimedAmountCents(DisputeTicket ticket) {
        if (ticket == null) {
            return null;
        }
        return sumLineAmountCents(enrichLines(parseItems(ticket.getItems())));
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
        merchantFeaturePackService.requireDevicePack(
                userId, session.getDeviceId(), MerchantFeaturePacks.BIZ);
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

    /** 结案时写入处理人展示名（姓名优先，否则手机号，再否则 userId）。 */
    private void applyAssignee(DisputeTicket ticket, Long actorId) {
        if (ticket == null || actorId == null) {
            return;
        }
        ticket.setAssignee(resolveActorDisplayName(actorId));
    }

    private String resolveActorDisplayName(Long actorId) {
        return userInfoRepository.findById(actorId)
                .map(u -> {
                    if (u.getName() != null && !u.getName().isBlank()) {
                        return u.getName().trim();
                    }
                    if (u.getPhoneNumber() != null && !u.getPhoneNumber().isBlank()) {
                        return u.getPhoneNumber().trim();
                    }
                    return String.valueOf(actorId);
                })
                .orElse(String.valueOf(actorId));
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
            return USER_APPEAL;
        }
        return switch (raw.trim().toUpperCase()) {
            case USER_APPEAL, RECOGNITION, "VIDEO_MISSING", "PAYMENT", "INVENTORY", "OTHER" ->
                    raw.trim().toUpperCase();
            default -> "OTHER";
        };
    }

    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return STATUS_NORMAL;
        }
        return switch (raw.trim().toUpperCase()) {
            case "LOW", STATUS_NORMAL, "HIGH", "URGENT" -> raw.trim().toUpperCase();
            default -> STATUS_NORMAL;
        };
    }

    private static String priorityForRecognition(VisionServiceClient.RecognitionResult recognition) {
        if (recognition == null) {
            return "HIGH";
        }
        if (recognition.items() == null || recognition.items().isEmpty()) {
            return "HIGH";
        }
        return recognition.overallConfidence() < 0.6f ? "HIGH" : STATUS_NORMAL;
    }

    static String reviewCodeFor(VisionServiceClient.RecognitionResult recognition, String reason) {
        List<String> detected = recognition != null && recognition.detectedClasses() != null
                ? recognition.detectedClasses() : List.of();
        boolean emptyItems = recognition == null
                || recognition.items() == null
                || recognition.items().isEmpty();
        String r = reason != null ? reason : "";
        String version = recognition != null && recognition.modelVersion() != null
                ? recognition.modelVersion().toLowerCase() : "";
        String gravityOrMock = reviewCodeForGravityOrMock(version, r);
        if (gravityOrMock != null) {
            return gravityOrMock;
        }
        if (emptyItems && !detected.isEmpty()) {
            return "UNMAPPED";
        }
        if (emptyItems || r.contains("未识别")) {
            return "EMPTY";
        }
        if (r.contains("置信") || r.contains("阈值")) {
            return "LOW_CONF";
        }
        if (r.contains("白名单") || r.contains("视觉状态") || r.contains("未登记")) {
            return "WHITELIST";
        }
        return "NEED_REVIEW";
    }

    private static String reviewCodeForGravityOrMock(String version, String reason) {
        if (version.contains("gravity-mismatch") || reason.contains("视觉与重力")) {
            return "GRAVITY_MISMATCH";
        }
        if (version.contains("mock") || version.contains("fallback")
                || reason.contains("模拟") || reason.contains("非生产精度")) {
            return "MOCK";
        }
        if (version.contains("gravity-fill") || reason.contains("仅有重力")) {
            return "GRAVITY_FILL";
        }
        return null;
    }

    private static String resolveReviewCode(DisputeTicket ticket) {
        if (ticket.getReviewCode() != null && !ticket.getReviewCode().isBlank()) {
            return ticket.getReviewCode().trim().toUpperCase();
        }
        if (!RECOGNITION.equals(ticket.getCategory())) {
            return null;
        }
        return reviewCodeFor(null, ticket.getReason());
    }

    private List<String> parseDetectedClasses(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw) || "[]".equals(raw.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String disputeTicketLockKey(String ticketId) {
        return "dispute:ticket:" + ticketId;
    }

    @FunctionalInterface
    private interface LockedOrderSupplier<T> {
        T get(CabinetOrder order);
    }

    private <T> T runWithOrderPaymentLock(String orderId, LockedOrderSupplier<T> action) {
        if (!distributedLockService.tryLock(OrderPaymentService.orderPaymentLockKey(orderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单支付处理中，请稍后重试");
        }
        try {
            CabinetOrder locked = orderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
            return action.get(locked);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(OrderPaymentService.orderPaymentLockKey(orderId));
        }
    }

    private <T> T runWithDisputeTicketLock(String ticketId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(disputeTicketLockKey(ticketId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "争议处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(disputeTicketLockKey(ticketId));
        }
    }
}
