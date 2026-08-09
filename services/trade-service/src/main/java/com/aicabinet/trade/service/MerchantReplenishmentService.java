package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.MerchantPortalGuard;
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
                                        FileAttachmentService fileAttachmentService) {
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
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> listSuggestions(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
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
        permissionService.requirePermission(userId, "merchant:replenishment:view");
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
        auditService.record(userId, "MERCHANT_REPLENISHMENT_CHECK_IN", "REPLENISHMENT_TASK",
                String.valueOf(taskId), "deviceId=" + task.getDeviceId());
        return result;
    }

    @Transactional
    public List<ReplenishmentTaskLineDto> getTaskLines(Long userId, Long taskId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        replenishmentService.ensureSeededFromLinkedRequest(taskId);
        return replenishmentService.listTaskLines(taskId);
    }

    @Transactional
    public List<ReplenishmentTaskLineDto> confirmTaskLines(Long userId, Long taskId,
                                                           SubmitReplenishmentLinesRequest body) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        List<ReplenishmentTaskLineDto> result = replenishmentService.submitTaskLines(userId, taskId, body);
        auditService.record(userId, "MERCHANT_REPLENISHMENT_CONFIRM_LINES", "REPLENISHMENT_TASK",
                String.valueOf(taskId), "deviceId=" + task.getDeviceId() + ",lines=" + result.size());
        return result;
    }

    @Transactional
    public ReplenishmentTaskDto completeTask(Long userId, Long taskId) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        ReplenishmentTaskDto result = replenishmentService.completeTask(userId, taskId);
        auditService.record(userId, "MERCHANT_REPLENISHMENT_COMPLETE", "REPLENISHMENT_TASK",
                String.valueOf(taskId), "deviceId=" + task.getDeviceId());
        return result;
    }

    /**
     * 商户/补货员现场开门：须已签到，绑定补货任务，不走消费者结算。
     */
    @Transactional
    public SessionDto openDoorForTask(Long userId, Long taskId) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        SessionDto session = opsService.openDoorForRestockAsUser(userId, task.getDeviceId(), taskId);
        auditService.record(userId, "MERCHANT_REPLENISHMENT_OPEN_DOOR", "REPLENISHMENT_TASK",
                String.valueOf(taskId),
                "deviceId=" + task.getDeviceId() + ",sessionId=" + session.sessionId());
        return session;
    }

    /** 签到/开门/确认上架/完成：与前端一致，需补货操作权（request） */
    private ReplenishmentTask requireScopedTask(Long userId, Long taskId) {
        permissionService.requirePermission(userId, "merchant:replenishment:request");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return task;
    }

    @Transactional(readOnly = true)
    public List<MerchantReplenishmentRequestDto> listRequests(Long userId, String status, String deviceId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
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
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        MerchantReplenishmentRequest request = requireRequest(requestId);
        merchantFeaturePackService.requireDevicePack(userId, request.getDeviceId(), MerchantFeaturePacks.FIELD);
        return toRequestDto(request);
    }

    @Transactional
    public MerchantReplenishmentRequestDto submitRequest(Long userId, CreateMerchantReplenishmentRequest body) {
        permissionService.requirePermission(userId, "merchant:replenishment:request");
        merchantPortalGuard.requireAccess(userId);
        if (body == null || body.deviceId() == null || body.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        if (body.lines() == null || body.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种商品");
        }
        String deviceId = body.deviceId().trim();
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
        request.setStatus("SUBMITTED");
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
        auditService.record(userId, "MERCHANT_REPLEN_REQUEST", "REPLEN_REQUEST",
                String.valueOf(request.getRequestId()), "device=" + deviceId);
        return toRequestDto(request);
    }

    @Transactional(readOnly = true)
    public List<MerchantReplenishmentRequestDto> listRequestsForOps(Long operatorId, String status) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        String statusFilter = blankToNull(status);
        if (statusFilter != null && "ALL".equalsIgnoreCase(statusFilter)) {
            statusFilter = null;
        }
        List<MerchantReplenishmentRequest> rows;
        if (statusFilter == null) {
            rows = requestRepository.findAllOrderBySubmittedAtDesc();
        } else if ("SUBMITTED".equalsIgnoreCase(statusFilter)) {
            // 待审核保持 FIFO，方便按提交顺序接单
            rows = requestRepository.findByStatusOrderBySubmittedAtAsc(statusFilter);
        } else {
            rows = requestRepository.findByStatusOrderBySubmittedAtDesc(statusFilter);
        }
        return rows.stream()
                .limit(200)
                .map(this::toRequestDto)
                .toList();
    }

    @Transactional
    public MerchantReplenishmentRequestDto acceptRequest(Long operatorId, Long requestId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        MerchantReplenishmentRequest request = requireRequest(requestId);
        if (!"SUBMITTED".equals(request.getStatus())) {
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
        auditService.record(operatorId, "MERCHANT_REPLEN_ACCEPT", "REPLEN_REQUEST",
                String.valueOf(requestId),
                "task=" + task.getTaskId() + (outboundId != null ? ",outbound=" + outboundId : ",outbound=none"));
        return toRequestDto(request);
    }

    @Transactional
    public MerchantReplenishmentRequestDto rejectRequest(Long operatorId, Long requestId,
                                                         RejectMerchantReplenishmentRequest body) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        MerchantReplenishmentRequest request = requireRequest(requestId);
        if (!"SUBMITTED".equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待审核要货单可驳回");
        }
        request.setStatus("REJECTED");
        request.setReviewedAt(Instant.now());
        request.setReviewerId(operatorId);
        request.setRejectReason(body != null ? trimToNull(body.reason()) : null);
        requestRepository.save(request);
        auditService.record(operatorId, "MERCHANT_REPLEN_REJECT", "REPLEN_REQUEST",
                String.valueOf(requestId), request.getRejectReason());
        return toRequestDto(request);
    }

    private MerchantReplenishmentRequest requireRequest(Long requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "要货单不存在"));
    }

    private MerchantReplenishmentRequestDto toRequestDto(MerchantReplenishmentRequest request) {
        DeviceInfo device = deviceRepository.findById(request.getDeviceId()).orElse(null);
        Merchant merchant = merchantRepository.findById(request.getMerchantId()).orElse(null);
        UserInfo creator = userInfoRepository.findById(request.getCreatedBy()).orElse(null);
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
                creator != null ? (creator.getName() != null ? creator.getName() : creator.getPhoneNumber()) : null,
                request.getSubmittedAt() != null ? request.getSubmittedAt() : request.getCreatedAt(),
                request.getReviewedAt(),
                request.getRejectReason(),
                request.getReplenishmentTaskId(),
                request.getOutboundId(),
                lines
        );
    }

    @Transactional
    public FileAttachmentDto uploadTaskEvidence(Long userId, Long taskId, org.springframework.web.multipart.MultipartFile file) {
        permissionService.requirePermission(userId, "merchant:replenishment:request");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return fileAttachmentService.uploadReplenishmentEvidence(userId, taskId, file);
    }

    @Transactional(readOnly = true)
    public List<FileAttachmentDto> listTaskEvidence(Long userId, Long taskId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return fileAttachmentService.listReplenishmentEvidence(taskId);
    }

    @Transactional(readOnly = true)
    public void streamTaskEvidence(Long userId, Long taskId, Long fileId, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
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
