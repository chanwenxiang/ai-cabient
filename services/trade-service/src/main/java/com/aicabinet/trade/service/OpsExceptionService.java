package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.OpsExceptionActionDto;
import com.aicabinet.common.dto.OpsExceptionDetailDto;
import com.aicabinet.trade.util.BizIds;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.service.support.OpsExceptionServiceSupport;
import com.aicabinet.common.dto.ResolveDisputeRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class OpsExceptionService {
    private static final String OPS_EXCEPTION = "OPS_EXCEPTION";
    private static final String PROCESSING = "PROCESSING";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private static final List<String> OPEN = List.of("OPEN", PROCESSING);
    private final OpsExceptionMapper repository;
    private final PermissionService permissionService;
    private final OpsExceptionServiceSupport support;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final OpsExceptionService self;

    public OpsExceptionService(OpsExceptionMapper repository, PermissionService permissionService,
                               OpsExceptionServiceSupport support,
                               DistributedLockService distributedLockService, @Lazy OpsExceptionService self) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.support = support;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    public record ExceptionReport(String type, String severity, ExceptionRefs refs, String title, String detail) {
        public record ExceptionRefs(String deviceId, String sessionId, String orderId, Long userId) {}

        public static ExceptionReport of(String type, String severity, ExceptionRefs refs, String title, String detail) {
            return new ExceptionReport(type, severity, refs, title, detail);
        }
    }

    @Transactional
    public OpsExceptionDto report(String type, String severity, ExceptionReport.ExceptionRefs refs,
                                  String title, String detail) {
        return report(ExceptionReport.of(type, severity, refs, title, detail));
    }

    @Transactional
    public OpsExceptionDto report(ExceptionReport request) {
        String dedup = request.type() + ":" + first(request.refs().sessionId(), request.refs().orderId(),
                request.refs().deviceId(), String.valueOf(request.refs().userId()));
        return runWithDedupLock(dedup, () -> repository.findFirstByDedupKeyAndStatusIn(dedup, OPEN).map(this::toDto).orElseGet(() -> {
            OpsException item = new OpsException();
            item.setExceptionId(BizIds.nextNumeric());
            item.setExceptionType(request.type());
            item.setSeverity(request.severity());
            item.setStatus("OPEN");
            item.setDeviceId(request.refs().deviceId());
            item.setSessionId(request.refs().sessionId());
            item.setOrderId(request.refs().orderId());
            item.setUserId(request.refs().userId());
            item.setTitle(request.title());
            item.setDetail(trim(request.detail()));
            item.setDedupKey(dedup);
            item.setSlaDueAt(slaDueFor(request.severity(), Instant.now()));
            return toDto(repository.save(item));
        }));
    }

    @Transactional(readOnly = true)
    public OpsExceptionDetailDto detail(Long operatorId, String exceptionId) {
        requireExceptionRead(operatorId);
        OpsException item = require(exceptionId);
        var actions = support.auditRepository().findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OPS_EXCEPTION, exceptionId)
                .stream().map(log -> new OpsExceptionActionDto(log.getLogId(), log.getOperatorId(),
                        log.getAction(), log.getDetail(), log.getCreatedAt())).toList();
        return new OpsExceptionDetailDto(toDto(item), actions);
    }


    private static Instant slaDueFor(String severity, Instant from) {
        String s = severity == null ? "" : severity.trim().toUpperCase();
        long hours = switch (s) {
            case "CRITICAL", "HIGH" -> 4;
            case "MEDIUM" -> 24;
            default -> 48;
        };
        return from.plusSeconds(hours * 3600);
    }

    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> list(Long operatorId, String status, String severity,
                                            boolean overdueOnly, Boolean archived, int page, int size) {
        requireExceptionRead(operatorId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        String severityFilter = severity == null || severity.isBlank() ? null : severity.trim().toUpperCase();
        var result = repository.findFiltered(statusFilter, severityFilter, overdueOnly, archived, pageable);
        return new PageResult<>(result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> list(Long operatorId, String status, String severity,
                                            boolean overdueOnly, int page, int size) {
        return self.list(operatorId, status, severity, overdueOnly, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> list(Long operatorId, String status, String severity, int page, int size) {
        return self.list(operatorId, status, severity, false, page, size);
    }

    /** @deprecated Prefer {@link #list(Long, String, String, boolean, int, int)} with severity/overdue. */
    @Deprecated(since = "0.1.0", forRemoval = false)
    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> list(Long operatorId, String status, int page, int size) {
        return self.list(operatorId, status, null, false, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<OpsExceptionDto> listForDevices(Set<String> deviceIds, String status, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (deviceIds != null && deviceIds.isEmpty()) return new PageResult<>(List.of(), page, size, 0);
        String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        Page<OpsException> result;
        if (deviceIds == null) {
            result = repository.findFiltered(statusFilter, null, false, pageable);
        } else if (statusFilter == null) {
            result = repository.findByDeviceIdInOrderByCreatedAtDesc(deviceIds, pageable);
        } else {
            result = repository.findByDeviceIdInAndStatusOrderByCreatedAtDesc(deviceIds, statusFilter, pageable);
        }
        return new PageResult<>(result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public OpsExceptionDto claim(Long operatorId, String exceptionId) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireForUpdate(exceptionId);
            if (STATUS_RESOLVED.equals(item.getStatus())) return toDto(item);
            item.setAssigneeUserId(operatorId); item.setStatus(PROCESSING); repository.save(item);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_CLAIM", OPS_EXCEPTION, exceptionId, item.getExceptionType());
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto resolve(Long operatorId, String exceptionId, String resolution) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireForUpdate(exceptionId);
            item.setAssigneeUserId(operatorId); item.setStatus(STATUS_RESOLVED); item.setResolution(trim(resolution));
            item.setResolvedAt(Instant.now()); repository.save(item);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_RESOLVE", OPS_EXCEPTION, exceptionId, trim(resolution));
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto archive(Long operatorId, String exceptionId) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireForUpdate(exceptionId);
            if (!STATUS_RESOLVED.equals(item.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已解决的异常可归档");
            }
            if (Boolean.TRUE.equals(item.getArchived())) {
                return toDto(item);
            }
            item.setArchived(true);
            item.setArchivedAt(Instant.now());
            repository.save(item);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_ARCHIVE", OPS_EXCEPTION, exceptionId,
                    "异常已归档：" + item.getExceptionId());
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto unarchive(Long operatorId, String exceptionId) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireForUpdate(exceptionId);
            if (!Boolean.TRUE.equals(item.getArchived())) {
                return toDto(item);
            }
            item.setArchived(false);
            item.setArchivedAt(null);
            repository.save(item);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_UNARCHIVE", OPS_EXCEPTION, exceptionId,
                    "异常取消归档：" + item.getExceptionId());
            return toDto(item);
        });
    }

    /**
     * 设备故障异常：创建维修工单并结案，避免逾期报修只关异常不落工单。
     */
    @Transactional
    public OpsExceptionDto resolveWithRepair(Long operatorId, String exceptionId, String resolution) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireForUpdate(exceptionId);
            if (STATUS_RESOLVED.equals(item.getStatus())) {
                return toDto(item);
            }
            if (!"DEVICE_FAULT".equalsIgnoreCase(item.getExceptionType())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "仅设备故障异常可一键建维修工单");
            }
            if (item.getDeviceId() == null || item.getDeviceId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "异常未关联设备，无法建维修工单");
            }
            String title = item.getTitle() == null || item.getTitle().isBlank()
                    ? "消费者设备报修" : item.getTitle().trim();
            String remark = (item.getDetail() == null ? "" : item.getDetail().trim() + "; ")
                    + "来自异常 " + exceptionId;
            var ticket = support.repairTicketService().create(
                    operatorId,
                    item.getDeviceId(),
                    title,
                    "OTHER",
                    String.valueOf(operatorId),
                    "HIGH",
                    remark);
            String text = trim(resolution) + "; repairTicketId=" + ticket.ticketId();
            item.setAssigneeUserId(operatorId);
            item.setStatus(STATUS_RESOLVED);
            item.setResolution(text);
            item.setResolvedAt(Instant.now());
            repository.save(item);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_RESOLVE_WITH_REPAIR", OPS_EXCEPTION, exceptionId, text);
            return toDto(item);
        });
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
        runWithSessionLock(sessionId, () -> {
            String text = trim(resolution);
            for (OpsException item : repository.findBySessionIdAndStatusIn(sessionId, OPEN)) {
                OpsException locked = requireForUpdate(item.getExceptionId());
                locked.setAssigneeUserId(operatorId);
                locked.setStatus(STATUS_RESOLVED);
                locked.setResolution(text);
                locked.setResolvedAt(Instant.now());
                repository.save(locked);
                support.auditService().appendLog(operatorId, "OPS_EXCEPTION_SYNC_FROM_DISPUTE", OPS_EXCEPTION,
                        locked.getExceptionId(), text);
            }
            return null;
        });
    }

    @Transactional
    public void resolveSystem(String type, String businessKey, String resolution) {
        String dedup = type + ":" + businessKey;
        runWithDedupLock(dedup, () -> {
            repository.findFirstByDedupKeyAndStatusIn(dedup, OPEN).ifPresent(item -> {
                OpsException locked = requireForUpdate(item.getExceptionId());
                locked.setStatus(STATUS_RESOLVED);
                locked.setResolution(trim(resolution));
                locked.setResolvedAt(Instant.now());
                repository.save(locked);
                support.auditService().appendLog(0L, "OPS_EXCEPTION_AUTO_RESOLVE", OPS_EXCEPTION,
                        locked.getExceptionId(), trim(resolution));
            });
            return null;
        });
    }

    @Transactional
    public OpsExceptionDto resolveForMerchant(Long merchantUserId, String exceptionId,
                                               Set<String> allowedDeviceIds, String resolution) {
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireOpenForUpdate(exceptionId);
            if (item.getDeviceId() == null || allowedDeviceIds == null
                    || !allowedDeviceIds.contains(item.getDeviceId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ACCESS_DENIED);
            }
            if (!Set.of("INVENTORY_MISMATCH", "LOW_STOCK", "REPLENISHMENT_REQUIRED")
                    .contains(item.getExceptionType())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "商家只能处理所属设备的库存类异常");
            }
            item.setAssigneeUserId(merchantUserId);
            item.setStatus(STATUS_RESOLVED);
            item.setResolution(trim(resolution));
            item.setResolvedAt(Instant.now());
            repository.save(item);
            support.auditService().appendLog(merchantUserId, "MERCHANT_OPS_EXCEPTION_RESOLVE", OPS_EXCEPTION,
                    exceptionId, trim(resolution));
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto transfer(Long operatorId, String exceptionId, Long assigneeUserId, String reason) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireOpenForUpdate(exceptionId);
            item.setAssigneeUserId(assigneeUserId);
            item.setStatus(PROCESSING);
            repository.save(item);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_TRANSFER", OPS_EXCEPTION, exceptionId,
                    "接收人：用户 " + assigneeUserId + "；原因：" + trim(reason));
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto addNote(Long operatorId, String exceptionId, String note) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            requireOpenForUpdate(exceptionId);
            support.auditService().appendLog(operatorId, "OPS_EXCEPTION_NOTE", OPS_EXCEPTION, exceptionId, trim(note));
            return toDto(require(exceptionId));
        });
    }

    @Transactional
    public OpsExceptionDto recordAction(Long operatorId, String exceptionId, String action,
                                        String idempotencyKey, String detail) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireOpenForUpdate(exceptionId);
            item.setAssigneeUserId(operatorId);
            item.setStatus(PROCESSING);
            repository.save(item);
            support.auditService().appendLog(operatorId, action, OPS_EXCEPTION, exceptionId,
                    "幂等键：" + idempotencyKey + "；" + trim(detail));
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto resolveByAction(Long operatorId, String exceptionId, String action,
                                           String idempotencyKey, String result) {
        requireExceptionHandle(operatorId);
        return runWithExceptionLock(exceptionId, () -> {
            OpsException item = requireForUpdate(exceptionId);
            if (STATUS_RESOLVED.equals(item.getStatus())) return toDto(item);
            item.setAssigneeUserId(operatorId);
            item.setStatus(STATUS_RESOLVED);
            item.setResolution(trim(result));
            item.setResolvedAt(Instant.now());
            repository.save(item);
            support.auditService().appendLog(operatorId, action, OPS_EXCEPTION, exceptionId,
                    "幂等键：" + idempotencyKey + "；结果：" + trim(result));
            return toDto(item);
        });
    }

    @Transactional
    public OpsExceptionDto manualResolve(Long operatorId, String exceptionId, String resolutionType,
                                         List<ResolveDisputeRequest.ManualLineItem> lines,
                                         String idempotencyKey, String reason) {
        requireExceptionHandle(operatorId);
        permissionService.requirePermission(operatorId, "ops:dispute:resolve");
        return runWithExceptionLock(exceptionId, () -> doManualResolve(operatorId, exceptionId, resolutionType,
                lines, idempotencyKey, reason));
    }

    private OpsExceptionDto doManualResolve(Long operatorId, String exceptionId, String resolutionType,
                                            List<ResolveDisputeRequest.ManualLineItem> lines,
                                            String idempotencyKey, String reason) {
        OpsException item = requireForUpdate(exceptionId);
        if (!Set.of("BALANCE_INSUFFICIENT", "RECOGNITION_UNAVAILABLE", "RECOGNITION_FAILED",
                "SETTLEMENT_FAILED").contains(item.getExceptionType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该异常类型不支持人工商品或资金处置");
        }
        String marker = "idempotencyKey=" + idempotencyKey;
        boolean replay = support.auditRepository().findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                        OPS_EXCEPTION, exceptionId).stream()
                .anyMatch(log -> log.getDetail() != null && log.getDetail().contains(marker));
        if (replay || STATUS_RESOLVED.equals(item.getStatus())) return toDto(item);
        if (item.getSessionId() == null || item.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该异常未关联购物会话");
        }
        var session = support.sessionRepository().findById(item.getSessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        String result = applyManualResolution(session, item, resolutionType, lines, reason);
        String type = resolutionType == null ? "" : resolutionType.trim().toUpperCase();
        finalizeManualResolve(new ManualResolveFinalization(
                operatorId, exceptionId, item, session,
                new ManualResolveFinalization.ManualResolveOutcome(type, lines, marker, result)));
        return toDto(item);
    }

    private String applyManualResolution(ShoppingSession session, OpsException item, String resolutionType,
                                         List<ResolveDisputeRequest.ManualLineItem> lines, String reason) {
        String type = resolutionType == null ? "" : resolutionType.trim().toUpperCase();
        if ("WAIVE".equals(type)) {
            int refunded = support.settlementService().waiveAndRefund(session);
            return "人工免单，退回余额 " + refunded + " 分；原因=" + reason;
        }
        if ("CONFIRM".equals(type) || "ADJUST".equals(type)) {
            var recognized = (lines == null ? List.<ResolveDisputeRequest.ManualLineItem>of() : lines).stream()
                    .filter(line -> line.quantity() > 0)
                    .map(line -> new VisionServiceClient.RecognizedItem(line.skuId(), line.quantity(), 1.0f))
                    .toList();
            var settled = support.settlementService().confirmDisputedItems(session, recognized);
            session.setOrderId(settled.order().orderId());
            item.setOrderId(settled.order().orderId());
            return "人工确认商品，原金额=" + settled.originalAmountCents()
                    + " 分，最终金额=" + settled.finalAmountCents()
                    + " 分，差额=" + settled.adjustmentCents() + " 分；原因=" + reason;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resolutionType 仅支持 CONFIRM、ADJUST、WAIVE");
    }

    private record ManualResolveFinalization(
            Long operatorId, String exceptionId, OpsException item, ShoppingSession session,
            ManualResolveOutcome outcome) {
        private record ManualResolveOutcome(
                String resolutionType, List<ResolveDisputeRequest.ManualLineItem> lines,
                String marker, String result) {}
    }

    private void finalizeManualResolve(ManualResolveFinalization ctx) {
        ctx.session().setState(SessionState.COMPLETED);
        ctx.session().setFailReason(null);
        support.sessionRepository().save(ctx.session());
        support.disputeService().closeOpenTicketForSession(
                ctx.operatorId(), ctx.session().getSessionId(),
                ctx.outcome().resolutionType(), ctx.outcome().lines());
        if ((ctx.item().getOrderId() == null || ctx.item().getOrderId().isBlank())
                && ctx.session().getOrderId() != null && !ctx.session().getOrderId().isBlank()) {
            ctx.item().setOrderId(ctx.session().getOrderId());
        }
        ctx.item().setAssigneeUserId(ctx.operatorId());
        ctx.item().setStatus(STATUS_RESOLVED);
        ctx.item().setResolution(trim(ctx.outcome().result()));
        ctx.item().setResolvedAt(Instant.now());
        repository.save(ctx.item());
        support.auditService().appendLog(ctx.operatorId(), "OPS_EXCEPTION_MANUAL_RESOLVE", OPS_EXCEPTION, ctx.exceptionId(),
                ctx.outcome().marker() + "; " + ctx.outcome().result());
    }

    private OpsException require(String id) { return repository.findById(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST)); }
    private OpsException requireForUpdate(String id) {
        return repository.findByIdForUpdate(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
    }
    private OpsException requireOpenForUpdate(String id) {
        OpsException item = requireForUpdate(id);
        if (STATUS_RESOLVED.equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "异常已解决，不能继续操作");
        }
        return item;
    }

    static String exceptionLockKey(String exceptionId) {
        return "ops:exception:" + exceptionId;
    }

    static String exceptionDedupLockKey(String dedupKey) {
        return "ops:exception:dedup:" + dedupKey;
    }

    static String exceptionSessionLockKey(String sessionId) {
        return "ops:exception:session:" + sessionId;
    }

    private <T> T runWithExceptionLock(String exceptionId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(exceptionLockKey(exceptionId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "异常处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(exceptionLockKey(exceptionId));
        }
    }

    private <T> T runWithDedupLock(String dedupKey, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(exceptionDedupLockKey(dedupKey), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "异常上报处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(exceptionDedupLockKey(dedupKey));
        }
    }

    private <T> T runWithSessionLock(String sessionId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(exceptionSessionLockKey(sessionId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话异常同步处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(exceptionSessionLockKey(sessionId));
        }
    }
    private OpsExceptionDto toDto(OpsException i) {
        Instant sla = i.getSlaDueAt();
        boolean open = "OPEN".equals(i.getStatus()) || PROCESSING.equals(i.getStatus());
        boolean overdue = open && sla != null && Instant.now().isAfter(sla);
        String orderId = i.getOrderId();
        Long userId = i.getUserId();
        // 审单落账后会话已有 orderId/userId，但历史异常行可能未回写；列表展示时从会话补齐
        if (i.getSessionId() != null && !i.getSessionId().isBlank()
                && ((orderId == null || orderId.isBlank()) || userId == null)) {
            var session = support.sessionRepository().findById(i.getSessionId());
            if (orderId == null || orderId.isBlank()) {
                orderId = session.map(ShoppingSession::getOrderId)
                        .filter(id -> id != null && !id.isBlank())
                        .orElse(null);
            }
            if (userId == null) {
                userId = session.map(ShoppingSession::getUserId).orElse(null);
            }
        }
        return new OpsExceptionDto(i.getExceptionId(), i.getExceptionType(),
            i.getSeverity(), i.getStatus(), i.getDeviceId(), i.getSessionId(), orderId, userId,
            i.getTitle(), i.getDetail(), i.getAssigneeUserId(), i.getResolution(), i.getCreatedAt(), i.getUpdatedAt(),
            i.getResolvedAt(), sla, overdue, Boolean.TRUE.equals(i.getArchived()), i.getArchivedAt());
    }
    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !"null".equals(v)) {
                return v;
            }
        }
        return "GLOBAL";
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.length() > 1000 ? v.substring(0, 1000) : v;
    }

    private void requireExceptionRead(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, "ops:exception:list", "ops:exception:handle");
    }

    private void requireExceptionHandle(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:exception:handle");
    }
}
