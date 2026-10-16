package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DisplayIds;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.support.SkuDisplayNames;
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
    private static final String REPLENISHMENT_TASK = "REPLENISHMENT_TASK";
    private static final String DEVICEID = "deviceId=";
    // AU-090 保守默认上限（取值口径待 DC-18 拍板；调整只动这两处，判定见自发起的行级/聚合）
    static final int MAX_REQUESTED_QTY_PER_LINE = 1000;
    static final int MAX_REQUESTED_QTY_PER_SKU = 5000;
    private static final String LITERAL = "补货任务不存在";


    private final PermissionService permissionService;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final ReplenishmentService replenishmentService;
    private final OpsService opsService;
    private final WarehouseService warehouseService;
    private final AdminAuditService auditService;
    private final DeviceInfoMapper deviceRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final ReplenishmentRouteMapper routeRepository;
    private final ReplenishmentTaskMapper taskRepository;
    private final FileAttachmentService fileAttachmentService;
    private final DistributedLockService distributedLockService;
    private final ShoppingSessionMapper shoppingSessionRepository;
    private final SystemConfigService systemConfigService;

    public MerchantReplenishmentService(PermissionService permissionService,
                                        MerchantFeaturePackService merchantFeaturePackService,
                                        MerchantPortalGuard merchantPortalGuard,
                                        ReplenishmentService replenishmentService,
                                        OpsService opsService,
                                        WarehouseService warehouseService,
                                        AdminAuditService auditService,
                                        DeviceInfoMapper deviceRepository,
                                        SkuCatalogMapper skuCatalogRepository,
                                        ReplenishmentRouteMapper routeRepository,
                                        ReplenishmentTaskMapper taskRepository,
                                        FileAttachmentService fileAttachmentService,
                                        DistributedLockService distributedLockService,
                                        ShoppingSessionMapper shoppingSessionRepository,
                                        SystemConfigService systemConfigService) {
        this.permissionService = permissionService;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.replenishmentService = replenishmentService;
        this.opsService = opsService;
        this.warehouseService = warehouseService;
        this.auditService = auditService;
        this.deviceRepository = deviceRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.routeRepository = routeRepository;
        this.taskRepository = taskRepository;
        this.fileAttachmentService = fileAttachmentService;
        this.distributedLockService = distributedLockService;
        this.shoppingSessionRepository = shoppingSessionRepository;
        this.systemConfigService = systemConfigService;
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
        return completeTask(userId, taskId, null);
    }

    /** CB-050 收尾：可选定位写入完成审计（证明到场，与盘点凭证定位同语义）。 */
    @Transactional
    public ReplenishmentTaskDto completeTask(Long userId, Long taskId,
                                             com.aicabinet.common.dto.MerchantReplenishmentCompleteRequest location) {
        ReplenishmentTask task = requireScopedTask(userId, taskId);
        assertMerchantFieldCompletionGates(taskId);
        ReplenishmentTaskDto result = replenishmentService.completeTask(userId, taskId);
        String locationDetail =
                location != null && location.latitude() != null && location.longitude() != null
                        ? String.format("；定位 %.6f,%.6f", location.latitude(), location.longitude())
                        : "";
        auditService.appendLog(userId, "MERCHANT_REPLENISHMENT_COMPLETE", REPLENISHMENT_TASK,
                String.valueOf(taskId), DEVICEID + task.getDeviceId() + locationDetail);
        return result;
    }

    /**
     * 商户现场完成：补货开门 / 现场凭证是否必填由系统参数控制（默认均 true）。
     * 运营后台 complete 不走本入口。
     */
    private void assertMerchantFieldCompletionGates(Long taskId) {
        boolean requireDoor = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_COMPLETE_REQUIRE_DOOR, true);
        if (requireDoor && !shoppingSessionRepository.existsByReplenishmentTaskId(taskId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ApiMessages.REPLENISHMENT_COMPLETE_DOOR_REQUIRED);
        }
        boolean requireEvidence = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_COMPLETE_REQUIRE_EVIDENCE, true);
        if (requireEvidence && fileAttachmentService.countReplenishmentEvidence(taskId) < 1) {
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
     * 商户端同步开门状态：以服务端补货会话为准（本地缓存仅作乐观展示）。
     */
    @Transactional(readOnly = true)
    public MerchantReplenishmentDoorSessionDto resolveDoorSession(Long userId, Long taskId) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_VIEW);
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, LITERAL));
        merchantFeaturePackService.requireDevicePack(userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return shoppingSessionRepository.findLatestByReplenishmentTaskId(taskId)
                .map(session -> new MerchantReplenishmentDoorSessionDto(
                        true,
                        session.getSessionId(),
                        session.getState() != null ? session.getState().name() : null))
                .orElseGet(() -> new MerchantReplenishmentDoorSessionDto(false, null, null));
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

    /**
     * 补货员自主发起补货（无审批）：校验当前用户是柜机所属分仓（home_warehouse_id）负责人后，
     * 直接从该分仓扣账生成出库 + 补货任务，直达补货员任务列表（SATELLITE_WAREHOUSE.md 口径：日常补柜不申请要货）。
     */
    @Transactional
    public ReplenishmentTaskDto selfInitiateTask(Long userId, MerchantSelfReplenishRequest body) {
        permissionService.requirePermission(userId, MERCHANT_REPLENISHMENT_REQUEST);
        merchantPortalGuard.requireAccess(userId);
        if (body == null || body.deviceId() == null || body.deviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 不能为空");
        }
        if (body.lines() == null || body.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种商品");
        }
        String deviceId = body.deviceId().trim();
        return runWithDeviceLock(deviceId, () -> doSelfInitiateTask(userId, body, deviceId));
    }

    private ReplenishmentTaskDto doSelfInitiateTask(Long userId, MerchantSelfReplenishRequest body, String deviceId) {
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        String homeWarehouseId = device.getHomeWarehouseId();
        if (homeWarehouseId == null || homeWarehouseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "柜机未绑定所属仓库，无法自主发起补货");
        }
        // 仓跟人走：仅分仓负责人可自主发起（历史任务/他人仓一律拒绝）
        warehouseService.requireWarehouseManagedBy(homeWarehouseId.trim(), userId);

        Map<String, Long> totals = new LinkedHashMap<>();
        for (MerchantSelfReplenishLine line : body.lines()) {
            if (line == null || line.skuId() == null || line.skuId().isBlank()) {
                continue;
            }
            int qty = line.qty() != null ? line.qty() : 0;
            if (qty <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "补货数量必须大于 0");
            }
            // AU-090：单行数量上界（此前仅判 >0，无上限）
            if (qty > MAX_REQUESTED_QTY_PER_LINE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "单品要货数量超过上限（最多 " + MAX_REQUESTED_QTY_PER_LINE + "）");
            }
            skuCatalogRepository.findById(line.skuId().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SKU_NOT_FOUND));
            // AU-090：同 SKU 聚合用 long 累加并夹上限——Integer::sum 溢出为负量会直贯出库行/seed 行
            String skuId = line.skuId().trim();
            long sum = totals.getOrDefault(skuId, 0L) + qty;
            if (sum > MAX_REQUESTED_QTY_PER_SKU) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "同一商品补货总量超过上限（最多 " + MAX_REQUESTED_QTY_PER_SKU + "）");
            }
            totals.put(skuId, sum);
        }
        if (totals.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一种有效商品");
        }
        Map<String, Integer> skuQty = new LinkedHashMap<>();
        totals.forEach((skuId, total) -> skuQty.put(skuId, total.intValue()));

        LocalDate planned = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        ReplenishmentRoute route = new ReplenishmentRoute();
        route.setRouteName(DisplayIds.selfReplenRouteName(planned, device.getDeviceName()));
        route.setPlannedDate(planned);
        route.setStatus("PLANNED");
        route = routeRepository.save(route);

        ReplenishmentTask task = new ReplenishmentTask();
        task.setRouteId(route.getRouteId());
        task.setDeviceId(deviceId);
        task.setStatus("PENDING");
        task.setNotes("self-initiated by user " + userId);
        task = taskRepository.save(task);

        // 出库与任务解耦：分仓无可用库存时仍生成补货任务（RESTORM 行兜底），避免同事务 rollback-only
        Long outboundId = warehouseService.tryCreateOutboundFromLines(
                route.getRouteId(), deviceId, userId, skuQty, null, "merchant self replenish");
        if (outboundId != null) {
            task.setOutboundId(outboundId);
            taskRepository.save(task);
            // 草稿出库行即可生成现场补货明细，无需等发运
            replenishmentService.generateLinesFromOutbound(outboundId);
        } else {
            replenishmentService.seedDraftRestockLines(task.getTaskId(), deviceId, skuQty);
        }

        auditService.appendLog(userId, "MERCHANT_SELF_REPLENISH", REPLENISHMENT_TASK,
                String.valueOf(task.getTaskId()),
                "device=" + deviceId + (outboundId != null ? ",outbound=" + outboundId : ",outbound=none"));
        return replenishmentService.getTaskDto(task.getTaskId());
    }

    static String replenishmentDeviceLockKey(String deviceId) {
        return "merchant:replen:device:" + deviceId;
    }

    private <T> T runWithDeviceLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(replenishmentDeviceLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备补货处理中，请稍后重试");
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

    private static String displayUserName(UserInfo user) {
        if (user == null) return null;
        if (user.getName() != null && !user.getName().isBlank()) return user.getName().trim();
        if (user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank()) return user.getPhoneNumber().trim();
        return user.getUserId() != null ? String.valueOf(user.getUserId()) : null;
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
