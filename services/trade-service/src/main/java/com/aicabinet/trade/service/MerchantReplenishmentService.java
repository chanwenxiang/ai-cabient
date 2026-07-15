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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantReplenishmentService {

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final ReplenishmentService replenishmentService;
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

    public MerchantReplenishmentService(PermissionService permissionService,
                                        MerchantScopeService merchantScopeService,
                                        MerchantPortalGuard merchantPortalGuard,
                                        ReplenishmentService replenishmentService,
                                        WarehouseService warehouseService,
                                        AdminAuditService auditService,
                                        DeviceInfoMapper deviceRepository,
                                        MerchantMapper merchantRepository,
                                        SkuCatalogMapper skuCatalogRepository,
                                        UserInfoMapper userInfoRepository,
                                        MerchantReplenishmentRequestMapper requestRepository,
                                        MerchantReplenishmentRequestLineMapper requestLineRepository,
                                        ReplenishmentRouteMapper routeRepository,
                                        ReplenishmentTaskMapper taskRepository) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.replenishmentService = replenishmentService;
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
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestDto> listSuggestions(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        merchantScopeService.requireDeviceAccess(userId, deviceId.trim());
        return replenishmentService.suggestForDevice(deviceId.trim());
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

    private ReplenishmentTask requireScopedTask(Long userId, Long taskId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantScopeService.requireDeviceAccess(userId, task.getDeviceId());
        return task;
    }

    @Transactional(readOnly = true)
    public List<MerchantReplenishmentRequestDto> listRequests(Long userId, String status, String deviceId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowedDevices = merchantScopeService.allowedDeviceIds(userId);
        if (allowedDevices != null && allowedDevices.isEmpty()) {
            return List.of();
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantScopeService.requireDeviceAccess(userId, deviceId.trim());
        }
        List<MerchantReplenishmentRequest> rows;
        if (allowedDevices != null) {
            rows = requestRepository.findByDeviceIdInOrderBySubmittedAtDesc(allowedDevices);
        } else {
            Set<String> merchants = merchantScopeService.allowedMerchantIds(userId);
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
        merchantScopeService.requireDeviceAccess(userId, request.getDeviceId());
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
        merchantScopeService.requireDeviceAccess(userId, deviceId);
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
        if (statusFilter == null) {
            statusFilter = "SUBMITTED";
        }
        return requestRepository.findByStatusOrderBySubmittedAtAsc(statusFilter).stream()
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
        try {
            WarehouseOutboundDto outbound = warehouseService.createOutboundFromLines(
                    route.getRouteId(), request.getDeviceId(), operatorId, skuQty, null);
            task.setOutboundId(outbound.outboundId());
            taskRepository.save(task);
            request.setOutboundId(outbound.outboundId());
        } catch (Exception ex) {
            taskRepository.save(task);
        }

        request.setStatus("ACCEPTED");
        request.setReviewedAt(Instant.now());
        request.setReviewerId(operatorId);
        request.setReplenishmentTaskId(task.getTaskId());
        requestRepository.save(request);
        auditService.record(operatorId, "MERCHANT_REPLEN_ACCEPT", "REPLEN_REQUEST",
                String.valueOf(requestId), "task=" + task.getTaskId());
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
                request.getSubmittedAt(),
                request.getReviewedAt(),
                request.getRejectReason(),
                request.getReplenishmentTaskId(),
                request.getOutboundId(),
                lines
        );
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
