package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.OpsExceptionActionDto;
import com.aicabinet.common.dto.OpsExceptionDetailDto;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;

@Service
public class OpsExceptionService {
    private static final List<String> OPEN = List.of("OPEN", "PROCESSING");
    private final OpsExceptionMapper repository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final AdminAuditLogMapper auditRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final SettlementService settlementService;
    private final DisputeService disputeService;

    public OpsExceptionService(OpsExceptionMapper repository, PermissionService permissionService,
                               AdminAuditService auditService, AdminAuditLogMapper auditRepository,
                               ShoppingSessionMapper sessionRepository, SettlementService settlementService,
                               DisputeService disputeService) {
        this.repository = repository; this.permissionService = permissionService; this.auditService = auditService;
        this.auditRepository = auditRepository;
        this.sessionRepository = sessionRepository; this.settlementService = settlementService;
        this.disputeService = disputeService;
    }

    @Transactional(readOnly = true)
    public OpsExceptionDetailDto detail(Long operatorId, String exceptionId) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = require(exceptionId);
        var actions = auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc("OPS_EXCEPTION", exceptionId)
                .stream().map(log -> new OpsExceptionActionDto(log.getLogId(), log.getOperatorId(),
                        log.getAction(), log.getDetail(), log.getCreatedAt())).toList();
        return new OpsExceptionDetailDto(toDto(item), actions);
    }

    @Transactional
    public OpsExceptionDto report(String type, String severity, String deviceId, String sessionId,
                                  String orderId, Long userId, String title, String detail) {
        String dedup = type + ":" + first(sessionId, orderId, deviceId, String.valueOf(userId));
        return repository.findFirstByDedupKeyAndStatusIn(dedup, OPEN).map(this::toDto).orElseGet(() -> {
            OpsException item = new OpsException();
            item.setExceptionId("EX" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase());
            item.setExceptionType(type); item.setSeverity(severity); item.setStatus("OPEN");
            item.setDeviceId(deviceId); item.setSessionId(sessionId); item.setOrderId(orderId); item.setUserId(userId);
            item.setTitle(title); item.setDetail(trim(detail)); item.setDedupKey(dedup);
            return toDto(repository.save(item));
        });
    }

    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> list(Long operatorId, String status, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = status == null || status.isBlank()
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase(), pageable);
        return new PageResult<>(result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> listForDevices(Set<String> deviceIds, String status, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (deviceIds == null || deviceIds.isEmpty()) return new PageResult<>(List.of(), page, size, 0);
        var result = status == null || status.isBlank()
                ? repository.findByDeviceIdInOrderByCreatedAtDesc(deviceIds, pageable)
                : repository.findByDeviceIdInAndStatusOrderByCreatedAtDesc(deviceIds, status.trim().toUpperCase(), pageable);
        return new PageResult<>(result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public OpsExceptionDto claim(Long operatorId, String exceptionId) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = require(exceptionId);
        if ("RESOLVED".equals(item.getStatus())) return toDto(item);
        item.setAssigneeUserId(operatorId); item.setStatus("PROCESSING"); repository.save(item);
        auditService.record(operatorId, "OPS_EXCEPTION_CLAIM", "OPS_EXCEPTION", exceptionId, item.getExceptionType());
        return toDto(item);
    }

    @Transactional
    public OpsExceptionDto resolve(Long operatorId, String exceptionId, String resolution) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = require(exceptionId);
        item.setAssigneeUserId(operatorId); item.setStatus("RESOLVED"); item.setResolution(trim(resolution));
        item.setResolvedAt(Instant.now()); repository.save(item);
        auditService.record(operatorId, "OPS_EXCEPTION_RESOLVE", "OPS_EXCEPTION", exceptionId, trim(resolution));
        return toDto(item);
    }

    /**
     * Closes all open/processing ops exceptions for a session when a dispute is resolved in admin.
     * Idempotent when no matching exceptions exist.
     */
    @Transactional
    public void resolveOpenForSession(Long operatorId, String sessionId, String resolution) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String text = trim(resolution);
        for (OpsException item : repository.findBySessionIdAndStatusIn(sessionId, OPEN)) {
            item.setAssigneeUserId(operatorId);
            item.setStatus("RESOLVED");
            item.setResolution(text);
            item.setResolvedAt(Instant.now());
            repository.save(item);
            auditService.record(operatorId, "OPS_EXCEPTION_SYNC_FROM_DISPUTE", "OPS_EXCEPTION",
                    item.getExceptionId(), text);
        }
    }

    @Transactional
    public void resolveSystem(String type, String businessKey, String resolution) {
        String dedup = type + ":" + businessKey;
        repository.findFirstByDedupKeyAndStatusIn(dedup, OPEN).ifPresent(item -> {
            item.setStatus("RESOLVED");
            item.setResolution(trim(resolution));
            item.setResolvedAt(Instant.now());
            repository.save(item);
            auditService.record(0L, "OPS_EXCEPTION_AUTO_RESOLVE", "OPS_EXCEPTION",
                    item.getExceptionId(), trim(resolution));
        });
    }

    @Transactional
    public OpsExceptionDto resolveForMerchant(Long merchantUserId, String exceptionId,
                                               Set<String> allowedDeviceIds, String resolution) {
        OpsException item = requireOpen(exceptionId);
        if (item.getDeviceId() == null || allowedDeviceIds == null
                || !allowedDeviceIds.contains(item.getDeviceId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ACCESS_DENIED);
        }
        if (!Set.of("INVENTORY_MISMATCH", "LOW_STOCK", "REPLENISHMENT_REQUIRED")
                .contains(item.getExceptionType())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "商家只能处理所属设备的库存类异常");
        }
        item.setAssigneeUserId(merchantUserId);
        item.setStatus("RESOLVED");
        item.setResolution(trim(resolution));
        item.setResolvedAt(Instant.now());
        repository.save(item);
        auditService.record(merchantUserId, "MERCHANT_OPS_EXCEPTION_RESOLVE", "OPS_EXCEPTION",
                exceptionId, trim(resolution));
        return toDto(item);
    }

    @Transactional
    public OpsExceptionDto transfer(Long operatorId, String exceptionId, Long assigneeUserId, String reason) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = requireOpen(exceptionId);
        item.setAssigneeUserId(assigneeUserId);
        item.setStatus("PROCESSING");
        repository.save(item);
        auditService.record(operatorId, "OPS_EXCEPTION_TRANSFER", "OPS_EXCEPTION", exceptionId,
                "assignee=" + assigneeUserId + "; reason=" + trim(reason));
        return toDto(item);
    }

    @Transactional
    public OpsExceptionDto addNote(Long operatorId, String exceptionId, String note) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = requireOpen(exceptionId);
        auditService.record(operatorId, "OPS_EXCEPTION_NOTE", "OPS_EXCEPTION", exceptionId, trim(note));
        return toDto(item);
    }

    @Transactional
    public OpsExceptionDto recordAction(Long operatorId, String exceptionId, String action,
                                        String idempotencyKey, String detail) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = requireOpen(exceptionId);
        item.setAssigneeUserId(operatorId);
        item.setStatus("PROCESSING");
        repository.save(item);
        auditService.record(operatorId, action, "OPS_EXCEPTION", exceptionId,
                "idempotencyKey=" + idempotencyKey + "; " + trim(detail));
        return toDto(item);
    }

    @Transactional
    public OpsExceptionDto resolveByAction(Long operatorId, String exceptionId, String action,
                                           String idempotencyKey, String result) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        OpsException item = require(exceptionId);
        if ("RESOLVED".equals(item.getStatus())) return toDto(item);
        item.setAssigneeUserId(operatorId);
        item.setStatus("RESOLVED");
        item.setResolution(trim(result));
        item.setResolvedAt(Instant.now());
        repository.save(item);
        auditService.record(operatorId, action, "OPS_EXCEPTION", exceptionId,
                "idempotencyKey=" + idempotencyKey + "; result=" + trim(result));
        return toDto(item);
    }

    @Transactional
    public OpsExceptionDto manualResolve(Long operatorId, String exceptionId, String resolutionType,
                                         List<ResolveDisputeRequest.ManualLineItem> lines,
                                         String idempotencyKey, String reason) {
        permissionService.requirePermission(operatorId, "ops:dashboard:view");
        permissionService.requirePermission(operatorId, "ops:dispute");
        OpsException item = require(exceptionId);
        if (!Set.of("BALANCE_INSUFFICIENT", "RECOGNITION_UNAVAILABLE", "RECOGNITION_FAILED",
                "SETTLEMENT_FAILED").contains(item.getExceptionType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该异常类型不支持人工商品或资金处置");
        }
        String marker = "idempotencyKey=" + idempotencyKey;
        boolean replay = auditRepository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                        "OPS_EXCEPTION", exceptionId).stream()
                .anyMatch(log -> log.getDetail() != null && log.getDetail().contains(marker));
        if (replay || "RESOLVED".equals(item.getStatus())) return toDto(item);
        if (item.getSessionId() == null || item.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该异常未关联购物会话");
        }
        var session = sessionRepository.findById(item.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        String type = resolutionType == null ? "" : resolutionType.trim().toUpperCase();
        String result;
        if ("WAIVE".equals(type)) {
            int refunded = settlementService.waiveAndRefund(session);
            result = "人工免单，退回余额 " + refunded + " 分；原因=" + reason;
        } else if ("CONFIRM".equals(type) || "ADJUST".equals(type)) {
            var recognized = (lines == null ? List.<ResolveDisputeRequest.ManualLineItem>of() : lines).stream()
                    .filter(line -> line.quantity() > 0)
                    .map(line -> new VisionServiceClient.RecognizedItem(line.skuId(), line.quantity(), 1.0f))
                    .toList();
            var settled = settlementService.confirmDisputedItems(session, recognized);
            session.setOrderId(settled.order().orderId());
            result = "人工确认商品，原金额=" + settled.originalAmountCents()
                    + " 分，最终金额=" + settled.finalAmountCents()
                    + " 分，差额=" + settled.adjustmentCents() + " 分；原因=" + reason;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolutionType 仅支持 CONFIRM、ADJUST、WAIVE");
        }
        session.setState(SessionState.COMPLETED);
        session.setFailReason(null);
        sessionRepository.save(session);
        disputeService.closeOpenTicketForSession(operatorId, session.getSessionId(), type, lines);
        item.setAssigneeUserId(operatorId);
        item.setStatus("RESOLVED");
        item.setResolution(trim(result));
        item.setResolvedAt(Instant.now());
        repository.save(item);
        auditService.record(operatorId, "OPS_EXCEPTION_MANUAL_RESOLVE", "OPS_EXCEPTION", exceptionId,
                marker + "; " + result);
        return toDto(item);
    }

    private OpsException require(String id) { return repository.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST)); }
    private OpsException requireOpen(String id) {
        OpsException item = require(id);
        if ("RESOLVED".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "异常已解决，不能继续操作");
        }
        return item;
    }
    private OpsExceptionDto toDto(OpsException i) { return new OpsExceptionDto(i.getExceptionId(), i.getExceptionType(),
            i.getSeverity(), i.getStatus(), i.getDeviceId(), i.getSessionId(), i.getOrderId(), i.getUserId(),
            i.getTitle(), i.getDetail(), i.getAssigneeUserId(), i.getResolution(), i.getCreatedAt(), i.getUpdatedAt(), i.getResolvedAt()); }
    private static String first(String... values) { for (String v : values) if (v != null && !v.isBlank() && !"null".equals(v)) return v; return "GLOBAL"; }
    private static String trim(String v) { if (v == null) return null; v=v.trim(); return v.length()>1000?v.substring(0,1000):v; }
}
