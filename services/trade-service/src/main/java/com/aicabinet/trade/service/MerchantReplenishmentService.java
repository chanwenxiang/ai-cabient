package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantReplenishmentService {
    private static final String MERCHANT_REPLENISHMENT_REQUEST = "merchant:replenishment:request";
    private static final String MERCHANT_REPLENISHMENT_VIEW = "merchant:replenishment:view";
    private static final String MERCHANT_REPLEN_REQUEST = "MERCHANT_REPLEN_REQUEST";
    private static final String REPLENISHMENT_TASK = "REPLENISHMENT_TASK";
    private static final String REPLEN_REQUEST = "REPLEN_REQUEST";
    private static final String DEVICEID = "deviceId=";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String LITERAL = "补货任务不存在";


    private final PermissionService permissionService;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final ReplenishmentService replenishmentService;
    private final OpsService opsService;
    private final WarehouseService warehouseService;
    private final AdminAuditService auditService;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantMapper merchantRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final UserInfoMapper userInfoRepository;
    private final MerchantReplenishmentRequestMapper requestRepository;
    private final MerchantReplenishmentRequestLineMapper requestLineRepository;
    private final ReplenishmentRouteMapper routeRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final FileAttachmentService fileAttachmentService;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ShoppingSessionMapper shoppingSessionRepository;
    private final MerchantReplenishmentService self;

    public MerchantReplenishmentService(PermissionService permissionService,
                                        MerchantFeaturePackService merchantFeaturePackService,
                                        MerchantPortalGuard merchantPortalGuard,
                                        ReplenishmentService replenishmentService,
                                        OpsService opsService,
                                        WarehouseService warehouseService,
                                        AdminAuditService auditService,
                                        DeviceInfoMapper deviceRepository,
                                        MerchantMapper merchantRepository,
                                        SkuCatalogMapper skuCatalogRepository,
                                        UserInfoMapper userInfoRepository,
                                        MerchantReplenishmentRequestMapper requestRepository,
                                        MerchantReplenishmentRequestLineMapper requestLineRepository,
                                        ReplenishmentRouteMapper routeRepository,
                                        ReplenishmentTaskMapper taskRepository,
                                        FileAttachmentService fileAttachmentService,
                                        DistributedLockService distributedLockService,
                                        ApprovalWorkflowService approvalWorkflowService,
                                        ShoppingSessionMapper shoppingSessionRepository,
                                        @Lazy MerchantReplenishmentService self) {
        this.permissionService = permissionService;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.replenishmentService = replenishmentService;
        this.opsService = opsService;
        this.warehouseService = warehouseService;
        this.auditService = auditService;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.userInfoRepository = userInfoRepository;
        this.requestRepository = requestRepository;
        this.requestLineRepository = requestLineRepository;
        this.routeRepository = routeRepository;
        this.taskRepository = taskRepository;
        this.fileAttachmentService = fileAttachmentService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.shoppingSessionRepository = shoppingSessionRepository;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> listSuggestions(Long userId, String deviceId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        merchantFeaturePackService.requireDevicePack(userId, deviceId.trim(), MerchantFeaturePacks.FIELD);
        return replenishmentService.suggestForDevice(deviceId.trim());
    }

    /** 补货员今日运营执行情况：分配给本人的任务完成统计（对标友智慧「运营执行情况」）。 */
    @Transactional(readOnly = true)
    public MerchantReplenishmentEfficiencyDto myEfficiency(Long userId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Instant since = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        List<ReplenishmentTask> tasks = taskRepository.findByAssigneeUserIdAndCreatedAtSince(userId, since);
        int completed = 0;
        int inProgress = 0;
        int pending = 0;
        for (ReplenishmentTask task : tasks) {
            if ("COMPLETED".equals(task.getStatus())) {
                completed++;
            } else if ("IN_PROGRESS".equals(task.getStatus())) {
                inProgress++;
            } else if ("PENDING".equals(task.getStatus())) {
                pending++;
            }
        }
        int assigned = tasks.size();
        double rate = assigned == 0 ? 0 : Math.round(completed * 10000.0 / assigned) / 100.0;
        return new MerchantReplenishmentEfficiencyDto(assigned, completed, inProgress, pending, rate);
    }

    @Transactional
    public ReplenishmentTaskDto checkInTask(Long userId, Long taskId, ReplenishmentCheckInRequest body) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        ReplenishmentTaskDto result = replenishmentService.checkInTask(userId, taskId, body);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_CHECK_IN", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId());
        return result;
    }

    @Transactional
    public List<ReplenishmentTaskLineDto> getTaskLines(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        replenishmentService.ensureSeededFromLinkedRequest(taskId);
        return replenishmentService.listTaskLines(taskId);
    }

    @Transactional
    public List<ReplenishmentTaskLineDto> confirmTaskLines(Long userId, Long taskId,
                                                           SubmitReplenishmentLinesRequest body) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        List<ReplenishmentTaskLineDto> result = replenishmentService.submitTaskLines(userId, taskId, body);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_CONFIRM_LINES", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId() + ",lines=" + result.size());
        return result;
    }

    @Transactional
    public ReplenishmentTaskDto completeTask(Long userId, Long taskId) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        assertMerchantFieldCompletionGates(taskId);
        ReplenishmentTaskDto result = replenishmentService.completeTask(userId, taskId);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_COMPLETE", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId());
        return result;
    }

    /**
     * 商户现场完成：须已补货开门 + 至少一张凭证（运营后台/联调 complete 不走本入口）。
     */
    private void assertMerchantFieldCompletionGates(Long taskId) {
        if (!shoppingSessionRepository.existsByReplenishmentTaskId(taskId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_DOOR_REQUIRED);
        }
        if (fileAttachmentService.countReplenishmentEvidence(taskId) < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_EVIDENCE_REQUIRED);
        }
    }

    /**
     * 扫码/手输柜机归属校验：须在当前账号 FIELD 包设备范围内。
     */
    @Transactional(readOnly = true)
    public void assertDeviceInFieldScope(Long userId, String deviceId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        String id = deviceId.trim();
        if (deviceRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND);
        }
        try {
            merchantFeaturePackService.requireDevicePack(userId, id, MerchantFeaturePacks.FIELD);
        } catch (ResponseStatusException ex) {
            int code = ex.getStatusCode().value();
            if (code == HttpStatus.FORBIDDEN.value() || code == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        ApiMessages.REPLENISHMENT_DEVICE_OUT_OF_SCOPE);
            }
            throw ex;
        }
    }

    /**
     * 商户/补货员现场开门：须已签到，绑定补货任务，不走消费者结算。
     */
    @Transactional
    public SessionDto openDoorForTask(Long userId, Long taskId) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        SessionDto session = opsService.openDoorForRestockAsUser(userId, task.getDeviceId(), taskId);
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_OPEN_DOOR", REPLENISHMENT_TASK,
                String.valueOf(taskId),
                DEVICEID + task.getDeviceId() + ",sessionId=" + session.sessionId());
        return session;
    }

    /** 签到/开门/确认上架/完成：与前端一致，需补货操作权（request） */
    private ReplenishmentTask requireScopedTask(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return task;
    }

    @Transactional(readOnly = true)
    public List<MerchantReplenishmentRequestDto> listRequests(Long userId, String status, String deviceId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowedDevices = merchantFeaturePackService.allowedDeviceIdsForPack(userId, MerchantFeaturePacks.FIELD);
        if (allowedDevices != null && allowedDevices.isEmpty()) {
            return List.of();
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantFeaturePackService.requireDevicePack(userId, deviceId.trim(), MerchantFeaturePacks.FIELD);
        }
        List<MerchantReplenishmentRequest> rows;
        if (allowedDevices != null) {
            rows = requestRepository.findByDeviceIdInOrderBySubmittedAtDesc(allowedDevices);
        } else {
            Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.FIELD);
            rows = requestRepository.findByMerchantIdInOrderBySubmittedAtDesc(merchants);
        }
        String statusFilter = blankToNull(status);
        return rows.stream()
                .filter(r -> statusFilter == null || statusFilter.equalsIgnoreCase(r.getStatus()))
                .filter(r -> deviceId == null || deviceId.isBlank() || deviceId.trim().equals(r.getDeviceId()))
                .limit(100)
                .map(this::toRequestDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public MerchantReplenishmentRequestDto getRequest(Long userId, Long requestId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        MerchantReplenishmentRequest request = requireRequest(requestId);
        merchantFeaturePackService.requireDevicePack(userId, request.getDeviceId(), MerchantFeaturePacks.FIELD);
        return toRequestDto(request);
    }

    @Transactional
    public MerchantReplenishmentRequestDto submitRequest(Long userId, CreateMerchantReplenishmentRequest body) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        if (body == null || body.deviceId() == null || body.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        if (body.lines() == null || body.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种商品");
        }
        String deviceId = body.deviceId().trim();
        return runWithDeviceLock(deviceId, () -> doSubmitRequest(userId, body, deviceId));
    }

    private MerchantReplenishmentRequestDto doSubmitRequest(Long userId,
                                                          CreateMerchantReplenishmentRequest body,
                                                          String deviceId) {
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        if (device.getMerchantId() == null || device.getMerchantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备未绑定商户");
        }

        Map<String, Integer> suggestBySku = replenishmentService.suggestForDevice(deviceId).stream()
                .collect(Collectors.toMap(ReplenishmentSuggestDto::skuId, ReplenishmentSuggestDto::suggestQty, Integer::sum));

        MerchantReplenishmentRequest request = new MerchantReplenishmentRequest();
        request.setMerchantId(device.getMerchantId());
        request.setDeviceId(deviceId);
        request.setStatus(SUBMITTED);
        request.setNotes(trimToNull(body.notes()));
        request.setCreatedBy(userId);
        Instant now = Instant.now();
        request.setSubmittedAt(now);
        request = requestRepository.save(request);

        for (CreateMerchantReplenishmentRequest.Line line : body.lines()) {
            if (line == null || line.skuId() == null || line.skuId().isBlank()) {
                continue;
            }
            int qty = line.requestedQty() != null ? line.requestedQty() : 0;
            if (qty <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要货数量必须大于 0");
            }
            SkuCatalog sku = skuCatalogRepository.findById(line.skuId().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SKU_NOT_FOUND));
            MerchantReplenishmentRequestLine row = new MerchantReplenishmentRequestLine();
            row.setRequestId(request.getRequestId());
            row.setSkuId(sku.getSkuId());
            row.setSkuName(sku.getSkuName());
            row.setSuggestedQty(suggestBySku.getOrDefault(sku.getSkuId(), 0));
            row.setRequestedQty(qty);
            requestLineRepository.save(row);
        }
        if (requestLineRepository.findByRequestIdOrderByLineIdAsc(request.getRequestId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种有效商品");
        }
        auditService.appendLog(userId, MERCHANT_REPLEN_REQUEST, REPLEN_REQUEST,
                String.valueOf(request.getRequestId()), "device=" + deviceId);
        approvalWorkflowService.start(
                MERCHANT_REPLEN_REQUEST,
                String.valueOf(request.getRequestId()),
                userId,
                "商户要货 #" + request.getRequestId() + " · " + deviceId);
        if (body.evidenceFileIds() != null && !body.evidenceFileIds().isEmpty()) {
            fileAttachmentService.bindEvidenceToReplenishmentRequest(
                    userId, request.getRequestId(), body.evidenceFileIds());
        }
        return toRequestDto(request);
    }

    @Transactional(readOnly = true)
    public List<MerchantReplenishmentRequestDto> listRequestsForOps(Long operatorId, String status) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return self.listRequestsForOpsPage(operatorId, status, 0, 200).items();
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantReplenishmentRequestDto> listRequestsForOpsPage(
            Long operatorId, String status, int page, int size) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        String statusFilter = blankToNull(status);
        if (statusFilter != null && "ALL".equalsIgnoreCase(statusFilter)) {
            statusFilter = null;
        }
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = requestRepository.searchPage(statusFilter != null ? statusFilter : "ALL", p, s);
        List<MerchantReplenishmentRequestDto> items = result.getRecords().stream()
                .map(this::toRequestDto)
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public MerchantReplenishmentRequestDto acceptRequest(Long operatorId, Long requestId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return runWithRequestLock(requestId, () -> doAcceptRequest(operatorId, requestId));
    }

    private MerchantReplenishmentRequestDto doAcceptRequest(Long operatorId, Long requestId) {
        MerchantReplenishmentRequest request = requireRequestForUpdate(requestId);
        if (!SUBMITTED.equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待审核要货单可接单");
        }
        List<MerchantReplenishmentRequestLine> lines = requestLineRepository.findByRequestIdOrderByLineIdAsc(requestId);
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要货单无商品行");
        }

        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteName("商户要货 #" + requestId);
        route.setPlannedDate(LocalDate.now());
        route.setStatus("PLANNED");
        route = routeRepository.save(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setRouteId(route.getRouteId());
        task.setDeviceId(request.getDeviceId());
        task.setStatus("PENDING");
        task.setRequestId(requestId);
        task.setNotes("merchant request " + requestId);
        task = taskRepository.save(task);

        Map<String, Integer> skuQty = new LinkedHashMap<>();
        for (MerchantReplenishmentRequestLine line : lines) {
            skuQty.merge(line.getSkuId(), line.getRequestedQty(), Integer::sum);
        }
        // 出库与接单解耦：仓库无可用库存时仍生成补货任务，避免同事务 rollback-only
        Long outboundId = warehouseService.tryCreateOutboundFromLines(
                route.getRouteId(), request.getDeviceId(), operatorId, skuQty, null);
        if (outboundId != null) {
            task.setOutboundId(outboundId);
            taskRepository.save(task);
            request.setOutboundId(outboundId);
            // 草稿出库行即可生成现场补货明细，无需等发运
            replenishmentService.generateLinesFromOutbound(outboundId);
        } else {
            // 无仓配：按要货数量 seed RESTOCK 行，避免商户打开空任务
            replenishmentService.seedDraftRestockLines(task.getTaskId(), request.getDeviceId(), skuQty);
        }

        request.setStatus("ACCEPTED");
        request.setReviewedAt(Instant.now());
        request.setReviewerId(operatorId);
        request.setReplenishmentTaskId(task.getTaskId());
        requestRepository.save(request);
        auditService.appendLog(operatorId, "MERCHANT_REPLEN_ACCEPT", REPLEN_REQUEST,
                String.valueOf(requestId),
                "task=" + task.getTaskId() + (outboundId != null ? ",outbound=" + outboundId : ",outbound=none"));
        approvalWorkflowService.completeApproved(
                operatorId, MERCHANT_REPLEN_REQUEST, String.valueOf(requestId), null);
        return toRequestDto(request);
    }

    @Transactional
    public MerchantReplenishmentRequestDto rejectRequest(Long operatorId, Long requestId,
                                                         RejectMerchantReplenishmentRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        return runWithRequestLock(requestId, () -> doRejectRequest(operatorId, requestId, body));
    }

    private MerchantReplenishmentRequestDto doRejectRequest(Long operatorId, Long requestId,
                                                            RejectMerchantReplenishmentRequest body) {
        MerchantReplenishmentRequest request = requireRequestForUpdate(requestId);
        if (!SUBMITTED.equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待审核要货单可驳回");
        }
        request.setStatus("REJECTED");
        request.setReviewedAt(Instant.now());
        request.setReviewerId(operatorId);
        request.setRejectReason(body != null ? trimToNull(body.reason()) : null);
        requestRepository.save(request);
        auditService.appendLog(operatorId, "MERCHANT_REPLEN_REJECT", REPLEN_REQUEST,
                String.valueOf(requestId), request.getRejectReason());
        approvalWorkflowService.completeRejected(
                operatorId,
                MERCHANT_REPLEN_REQUEST,
                String.valueOf(requestId),
                request.getRejectReason());
        return toRequestDto(request);
    }

    private MerchantReplenishmentRequest requireRequest(Long requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "要货单不存在"));
    }

    private MerchantReplenishmentRequest requireRequestForUpdate(Long requestId) {
        return requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "要货单不存在"));
    }

    static String replenishmentRequestLockKey(Long requestId) {
        return "merchant:replen:request:" + requestId;
    }

    static String replenishmentDeviceLockKey(String deviceId) {
        return "merchant:replen:device:" + deviceId;
    }

    private <T> T runWithDeviceLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(replenishmentDeviceLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备要货处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(replenishmentDeviceLockKey(deviceId));
        }
    }

    private <T> T runWithRequestLock(Long requestId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(replenishmentRequestLockKey(requestId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "要货单处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(replenishmentRequestLockKey(requestId));
        }
    }

    private MerchantReplenishmentRequestDto toRequestDto(MerchantReplenishmentRequest request) {
        DeviceInfo device = deviceRepository.findById(request.getDeviceId()).orElse(null);
        Merchant merchant = merchantRepository.findById(request.getMerchantId()).orElse(null);
        UserInfo creator = userInfoRepository.findById(request.getCreatedBy()).orElse(null);
        UserInfo reviewer = request.getReviewerId() != null
                ? userInfoRepository.findById(request.getReviewerId()).orElse(null)
                : null;
        List<MerchantReplenishmentRequestLineDto> lines = requestLineRepository
                .findByRequestIdOrderByLineIdAsc(request.getRequestId()).stream()
                .map(l -> new MerchantReplenishmentRequestLineDto(
                        l.getLineId(), l.getSkuId(), l.getSkuName(), l.getSuggestedQty(), l.getRequestedQty()))
                .toList();
        return new MerchantReplenishmentRequestDto(
                request.getRequestId(),
                request.getMerchantId(),
                merchant != null ? merchant.getMerchantName() : request.getMerchantId(),
                request.getDeviceId(),
                device != null ? device.getDeviceName() : request.getDeviceId(),
                request.getStatus(),
                request.getNotes(),
                request.getCreatedBy(),
                displayUserName(creator),
                request.getSubmittedAt() != null ? request.getSubmittedAt() : request.getCreatedAt(),
                request.getReviewedAt(),
                request.getReviewerId(),
                displayUserName(reviewer),
                request.getRejectReason(),
                request.getReplenishmentTaskId(),
                request.getOutboundId(),
                lines,
                fileAttachmentService.countReplenishmentRequestEvidence(request.getRequestId())
        );
    }

    private static String displayUserName(UserInfo user) {
        if (user == null) return null;
        if (user.getName() != null && !user.getName().isBlank()) return user.getName().trim();
        if (user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank()) return user.getPhoneNumber().trim();
        return user.getUserId() != null ? String.valueOf(user.getUserId()) : null;
    }

    @Transactional
    public FileAttachmentDto uploadPendingRequestEvidence(Long userId, org.springframework.web.multipart.MultipartFile file) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        return fileAttachmentService.uploadPendingReplenishmentRequestEvidence(userId, file);
    }

    @Transactional(readOnly = true)
    public List<FileAttachmentDto> listRequestEvidence(Long userId, Long requestId) {
        MerchantReplenishmentRequest request = requireScopedRequest(userId, requestId);
        return fileAttachmentService.listReplenishmentRequestEvidence(request.getRequestId());
    }

    @Transactional(readOnly = true)
    public void streamRequestEvidence(Long userId, Long requestId, Long fileId,
                                      jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        requireScopedRequest(userId, requestId);
        FileAttachment row = fileAttachmentService.requireReplenishmentRequestEvidence(requestId, fileId);
        fileAttachmentService.stream(row, response);
    }

    private MerchantReplenishmentRequest requireScopedRequest(Long userId, Long requestId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        MerchantReplenishmentRequest request = requireRequest(requestId);
        merchantFeaturePackService.requireDevicePack(userId, request.getDeviceId(), MerchantFeaturePacks.FIELD);
        return request;
    }

    @Transactional
    public FileAttachmentDto uploadTaskEvidence(Long userId, Long taskId, org.springframework.web.multipart.MultipartFile file) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return fileAttachmentService.uploadReplenishmentEvidence(userId, taskId, file);
    }

    @Transactional(readOnly = true)
    public List<FileAttachmentDto> listTaskEvidence(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return fileAttachmentService.listReplenishmentEvidence(taskId);
    }

    @Transactional(readOnly = true)
    public void streamTaskEvidence(Long userId, Long taskId, Long fileId, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        FileAttachment row = fileAttachmentService.requireReplenishmentEvidence(taskId, fileId);
        fileAttachmentService.stream(row, response);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
