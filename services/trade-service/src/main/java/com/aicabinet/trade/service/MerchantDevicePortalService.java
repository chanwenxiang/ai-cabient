package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DeviceNameSupport;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantGatherStatusDto;
import com.aicabinet.trade.dto.JiangyiGatherDtos.MerchantTrainingStatusDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 商户门户设备 / 温控 / 货道 / 设备报表（原 MerchantPortalService device 簇）。
 * Pass 3F：从神类拆出，门面仍可委托本类。
 */
@Service
public class MerchantDevicePortalService {

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    private final PermissionService permissionService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final DeviceInfoMapper deviceRepository;
    private final DeviceSlotService deviceSlotService;
    private final AdminAuditService auditService;
    private final DeviceTemperatureReadingMapper temperatureReadingRepository;
    private final DeviceServiceClient deviceServiceClient;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final MerchantMapper merchantRepository;
    private final DistributedLockService distributedLockService;
    private final JiangyiGatherService jiangyiGatherService;
    private final InventoryLotService inventoryLotService;
    private final MerchantOpsPolicyService merchantOpsPolicyService;
    private final DeviceValidationService deviceValidationService;
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    private final com.aicabinet.trade.mapper.DeviceOpsEventMapper opsEventRepository;
    private final com.aicabinet.trade.mapper.DeviceSkuInventoryMapper inventoryRepository;
    private final SessionService sessionService;
    private final MerchantDevicePortalService self;
    /** 会话设备名快照（写入时固化）；盘点开门此前不回填 → 列表显示编号，2026-10-30 修复。 */
    private final DisplaySnapshotHelper displaySnapshotHelper;

    public MerchantDevicePortalService(PermissionService permissionService,
                                       MerchantPortalGuard merchantPortalGuard,
                                       MerchantFeaturePackService merchantFeaturePackService,
                                       DeviceInfoMapper deviceRepository,
                                       DeviceSlotService deviceSlotService,
                                       AdminAuditService auditService,
                                       DeviceTemperatureReadingMapper temperatureReadingRepository,
                                       DeviceServiceClient deviceServiceClient,
                                       ShoppingSessionMapper sessionRepository,
                                       CabinetOrderMapper orderRepository,
                                       ReplenishmentTaskMapper replenishmentTaskRepository,
                                       MerchantMapper merchantRepository,
                                       DistributedLockService distributedLockService,
                                       JiangyiGatherService jiangyiGatherService,
                                       InventoryLotService inventoryLotService,
                                       MerchantOpsPolicyService merchantOpsPolicyService,
                                       DeviceValidationService deviceValidationService,
                                       JiangyiDeviceDirectory jiangyiDeviceDirectory,
                                       com.aicabinet.trade.mapper.DeviceOpsEventMapper opsEventRepository,
                                       com.aicabinet.trade.mapper.DeviceSkuInventoryMapper inventoryRepository,
                                       @org.springframework.context.annotation.Lazy SessionService sessionService,
                                       @org.springframework.context.annotation.Lazy MerchantDevicePortalService self,
                                       DisplaySnapshotHelper displaySnapshotHelper) {
        this.permissionService = permissionService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.deviceRepository = deviceRepository;
        this.deviceSlotService = deviceSlotService;
        this.auditService = auditService;
        this.temperatureReadingRepository = temperatureReadingRepository;
        this.deviceServiceClient = deviceServiceClient;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.merchantRepository = merchantRepository;
        this.distributedLockService = distributedLockService;
        this.jiangyiGatherService = jiangyiGatherService;
        this.inventoryLotService = inventoryLotService;
        this.merchantOpsPolicyService = merchantOpsPolicyService;
        this.deviceValidationService = deviceValidationService;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.opsEventRepository = opsEventRepository;
        this.inventoryRepository = inventoryRepository;
        this.sessionService = sessionService;
        this.self = self;
        this.displaySnapshotHelper = displaySnapshotHelper;
    }

    @Transactional(readOnly = true)
    public List<MerchantDeviceDto> listDevices(Long userId) {
        permissionService.requirePermission(userId, "merchant:devices:list");
        merchantPortalGuard.requireAccess(userId);
        return buildDeviceDtos(merchantFeaturePackService.allowedDevicesForPack(userId, MerchantFeaturePacks.FIELD));
    }

    @Transactional(readOnly = true)
    public DeviceDetailDto getDeviceDetail(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:detail");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        return deviceSlotService.getDeviceDetail(userId, deviceId);
    }

    @Transactional(readOnly = true)
    public MerchantDeviceSettingsDto getDeviceSettings(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:detail");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        return toDeviceSettings(device);
    }

    // ---------- 将邑采集模式（CB-023 商户端补充）：与 admin 入口共用 JiangyiGatherService 与 409 闸门 ----------

    /** 商户侧采集状态：非将邑绑定柜机返回 bound=false（前端隐藏入口，不报错）。 */
    @Transactional(readOnly = true)
    public MerchantGatherStatusDto getJiangyiGatherStatus(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:detail");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        return jiangyiGatherService.merchantStatus(deviceId);
    }

    /**
     * 进入采集模式（柜机归属经 requireDevicePack 校验）。不加事务：markGatherLocked 落库与
     * 将邑 HTTP 开门必须成对且失败回滚锁，长事务包外呼是反模式——与 admin 路径语义一致。
     */
    public void startJiangyiGather(Long userId, String deviceId, String doorPosition) {
        permissionService.requirePermission(userId, "merchant:devices:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        jiangyiGatherService.startGather(userId, deviceId, doorPosition);
    }

    /** 退出采集模式（恢复营业）。 */
    public void exitJiangyiGather(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        jiangyiGatherService.exitGatherMode(userId, deviceId);
    }

    /** 只读学习进度：本地票据聚合（不读厂商读面），权限与归属同一套闸门。 */
    @Transactional(readOnly = true)
    public MerchantTrainingStatusDto getJiangyiGatherTrainingStatus(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:detail");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        return jiangyiGatherService.merchantTrainingStatus(deviceId);
    }

    @Transactional
    public MerchantDeviceSettingsDto updateDeviceSettings(Long userId, String deviceId,
                                                          UpdateMerchantDeviceSettingsRequest request) {
        permissionService.requirePermission(userId, "merchant:devices:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        return runWithDeviceSettingsLock(deviceId, () -> doUpdateDeviceSettings(userId, deviceId, request));
    }

    /**
     * 商户盘点开门（2026-10-28）：开门柜逐商品盘点前的开门动作。
     * <p>流程=先扫码选中柜机（人在柜边）→ 本接口落 STOCKTAKE 会话并经
     * {@link DeviceServiceClient#requestOpenDoorStock} 下发将邑 sendOpenStockDoor（与补货开门
     * 同一真路由）；关门上报（§4.2.14）按设备推进，{@code SessionDoorService} 对 STOCKTAKE
     * 会话关门即完成——不识别、不结算。占柜期间消费者开门 409（ensureNoBlockingSession）。</p>
     * <p>权限同货道盘点（merchant:slots:edit）；仅开门柜；须在线且无占用会话（允许锁机盘点）。</p>
     */
    public SessionDto openStocktakeDoor(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:slots:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        if (!jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅开门柜支持盘点开门；弹簧柜走货道实盘无需开门");
        }
        String lockKey = SessionService.sessionOpenLockKey(deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备开门处理中，请稍后重试");
        }
        try {
            return doOpenStocktakeDoor(userId, deviceId);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private SessionDto doOpenStocktakeDoor(Long userId, String deviceId) {
        deviceValidationService.ensureOpsRemoteDoorAllowed(deviceId);
        String sessionId = com.aicabinet.trade.util.BizIds.nextNumeric();
        self.persistStocktakeOpeningSession(userId, deviceId, sessionId);
        try {
            deviceServiceClient.requestOpenDoorStock(sessionId, deviceId, userId);
        } catch (ResponseStatusException e) {
            self.markStocktakeSessionFailed(sessionId, e.getReason() != null ? e.getReason() : "开门指令下发失败");
            throw e;
        } catch (Exception e) {
            self.markStocktakeSessionFailed(sessionId, "开门指令下发失败");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "开门指令下发失败（请确认设备在线）", e);
        }
        auditService.appendLog(userId, "MERCHANT_STOCKTAKE_OPEN_DOOR", "SESSION", sessionId,
                "deviceId=" + deviceId + "（盘点开门，扫码后发起）");
        return sessionService.getSession(userId, sessionId);
    }

    /** STOCKTAKE 会话落库（短事务，与 MQTT 下发分离；同 persistRestockOpeningSession 模式）。 */
    @Transactional
    public void persistStocktakeOpeningSession(Long userId, String deviceId, String sessionId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setDeviceId(deviceId);
        session.setIdempotencyKey("STOCKTAKE:" + userId + ":" + sessionId);
        session.setState(SessionState.OPENING);
        // 设备名快照写入时固化（与消费者开门同款）：缺了会话列表只好回退显示设备编号
        displaySnapshotHelper.applySessionDeviceName(session);
        sessionRepository.save(session);
    }

    @Transactional
    public void markStocktakeSessionFailed(String sessionId, String failReason) {
        ShoppingSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (session.getState() == SessionState.FAILED
                || session.getState() == SessionState.CANCELLED
                || session.getState() == SessionState.COMPLETED) {
            return;
        }
        session.setFailReason(failReason);
        sessionService.transition(session, SessionState.FAILED);
    }

    /**
     * 开门柜盘点提交：逐商品实盘覆盖式调账（唯一入口，2026-10-28）。
     * <p>台账→实盘差值自动加/减：{@link InventoryLotService#stocktakeAdjust} 在锁内
     * 动批次+台账并同步聚合并（三层账一起对，铁律 79）；事件落 AISLE_AUDIT
     * 与货道盘点同池（理货记录-盘点可查）。同 SKU 重复行 fail-closed 拒绝。</p>
     */
    @Transactional
    public MerchantDoorStocktakeResult submitDoorStocktake(Long userId, String deviceId,
                                                           MerchantDoorStocktakeRequest request) {
        permissionService.requirePermission(userId, "merchant:slots:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        if (!jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅开门柜支持按商品盘点");
        }
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "盘点明细不能为空");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MerchantDoorStocktakeRequest.StocktakeLine line : request.lines()) {
            if (line.skuId() == null || line.skuId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "盘点行缺少 skuId");
            }
            if (!seen.add(line.skuId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "同一商品重复提交：" + line.skuId() + "（请合并为一行实盘总数）");
            }
        }
        merchantOpsPolicyService.requirePhotoEvidence(deviceId, true, request.photoEvidenceUrl());

        Map<String, Integer> before = new java.util.HashMap<>();
        for (com.aicabinet.trade.domain.DeviceSkuInventory inv : inventoryRepository.findByIdDeviceId(deviceId)) {
            before.put(inv.getSkuId(), inv.getQuantity());
        }
        String refId = com.aicabinet.trade.util.BizIds.nextNumeric();
        List<MerchantDoorStocktakeResult.LineResult> results = new java.util.ArrayList<>(request.lines().size());
        for (MerchantDoorStocktakeRequest.StocktakeLine line : request.lines()) {
            int beforeQty = before.getOrDefault(line.skuId(), 0);
            inventoryLotService.stocktakeAdjust(deviceId, line.skuId(), line.countedQuantity(), userId, refId);
            int afterQty = inventoryRepository
                    .findById(new com.aicabinet.trade.domain.DeviceSkuInventoryId(deviceId, line.skuId()))
                    .map(com.aicabinet.trade.domain.DeviceSkuInventory::getQuantity)
                    .orElse(0);
            results.add(new MerchantDoorStocktakeResult.LineResult(line.skuId(), beforeQty, afterQty,
                    afterQty - beforeQty));
        }
        opsEventRepository.insert(newStocktakeEvent(deviceId, refId, results,
                request.photoEvidenceUrl(), request.latitude(), request.longitude()));
        auditService.appendLog(userId, "MERCHANT_DEVICE_STOCKTAKE", "DEVICE", deviceId,
                "ref=" + refId + "；行数=" + results.size());
        return new MerchantDoorStocktakeResult(deviceId, refId, results);
    }

    private com.aicabinet.trade.domain.DeviceOpsEvent newStocktakeEvent(
            String deviceId, String refId, List<MerchantDoorStocktakeResult.LineResult> results,
            String evidenceUrl, Double latitude, Double longitude) {
        String location = latitude != null && longitude != null
                ? "；定位 %.6f,%.6f".formatted(latitude, longitude)
                : "";
        String diffs = results.stream()
                .map(r -> "%s %d→%d（%s%d）".formatted(r.skuId(), r.quantityBefore(), r.quantityAfter(),
                        r.delta() > 0 ? "+" : "", r.delta()))
                .collect(java.util.stream.Collectors.joining("；"));
        com.aicabinet.trade.domain.DeviceOpsEvent e = new com.aicabinet.trade.domain.DeviceOpsEvent();
        e.setDeviceId(deviceId);
        e.setEventType("AISLE_AUDIT");
        e.setSeverity("INFO");
        e.setTitle("开门柜盘点 " + refId);
        e.setDetail(diffs
                + (evidenceUrl != null && !evidenceUrl.isBlank() ? "；凭证=有" : "；凭证=无")
                + location);
        e.setCreatedAt(Instant.now());
        return e;
    }

    private MerchantDeviceSettingsDto doUpdateDeviceSettings(Long userId, String deviceId,
                                                           UpdateMerchantDeviceSettingsRequest request) {
        DeviceInfo device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));

        applyDeviceSettingFields(device, request);
        deviceRepository.save(device);
        TempCommandResult tempResult = dispatchTargetTempIfRequested(device, request.targetTempC());
        auditService.appendLog(userId, "MERCHANT_DEVICE_SETTINGS", "DEVICE", deviceId,
                "名称：" + device.getDeviceName());
        return toDeviceSettings(device, tempResult.sent(), tempResult.message());
    }

    private void applyDeviceSettingFields(DeviceInfo device, UpdateMerchantDeviceSettingsRequest request) {
        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            device.setDeviceName(request.deviceName().trim());
        }
        if (request.alertContactName() != null) {
            device.setAlertContactName(blankToNull(request.alertContactName()));
        }
        if (request.alertContactPhone() != null) {
            device.setAlertContactPhone(blankToNull(request.alertContactPhone()));
        }
        if (request.targetTempC() != null) {
            int temp = request.targetTempC();
            if (temp < -30 || temp > 30) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标温度应在 -30°C ~ 30°C 之间");
            }
            device.setTargetTempC(temp);
        }
        if (request.opsRemark() != null) {
            device.setOpsRemark(blankToNull(request.opsRemark()));
        }
    }

    private TempCommandResult dispatchTargetTempIfRequested(DeviceInfo device, Integer targetTempC) {
        if (targetTempC == null) {
            return TempCommandResult.none();
        }
        if (CabinetConstants.DEVICE_ONLINE.equalsIgnoreCase(device.getOnlineStatus())) {
            try {
                deviceServiceClient.requestSetTargetTemp(device.getDeviceId(), targetTempC);
                return new TempCommandResult(true, "已向柜机下发目标温度 " + targetTempC + "°C");
            } catch (Exception ex) {
                return new TempCommandResult(false, "设置已保存，柜机指令下发失败（请确认 device-service 在线）");
            }
        }
        return new TempCommandResult(false, "设置已保存，柜机离线时将在上线后手动同步");
    }

    private record TempCommandResult(Boolean sent, String message) {
        static TempCommandResult none() {
            return new TempCommandResult(null, null);
        }
    }

    @Transactional(readOnly = true)
    public List<DeviceTemperatureReadingDto> getTemperatureHistory(Long userId, String deviceId, int hours) {
        permissionService.requirePermission(userId, "merchant:temp:history");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        int clampedHours = Math.min(Math.max(hours, 1), 168);
        Instant since = Instant.now().minus(clampedHours, ChronoUnit.HOURS);
        // 按小时放大上限，硬顶 5000，避免长窗口 + 高频上报拖垮接口
        int pointLimit = Math.min(5000, Math.max(DeviceTemperatureReadingMapper.DEFAULT_HISTORY_LIMIT, clampedHours * 30));
        return temperatureReadingRepository.findByDeviceIdSince(deviceId, since, pointLimit).stream()
                .map(r -> new DeviceTemperatureReadingDto(r.getDeviceId(), r.getTempC(), r.getReportedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantDeviceReportDto> deviceReports(Long userId) {
        permissionService.requirePermission(userId, "merchant:reports:view");
        merchantPortalGuard.requireAccess(userId);
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findByStateIn(ACTIVE_STATES, 2000).stream()
                .collect(Collectors.toMap(ShoppingSession::getDeviceId, s -> s, (a, b) -> a));

        return merchantFeaturePackService.allowedDevicesForPack(userId, MerchantFeaturePacks.BIZ).stream()
                .map(d -> {
                    String id = d.getDeviceId();
                    long orderTotal = orderRepository.countByDeviceId(id);
                    long revenueTotal = orderRepository.sumAmountByDeviceId(id);
                    long orderToday = orderRepository.countByDeviceIdAndCreatedAtAfter(id, todayStart);
                    long revenueToday = orderRepository.sumAmountByDeviceIdSince(id, todayStart);
                    return new MerchantDeviceReportDto(
                            id,
                            DeviceNameSupport.resolve(id, d.getDeviceName()),
                            d.getOnlineStatus(),
                            orderTotal,
                            revenueTotal,
                            orderToday,
                            revenueToday,
                            sessionRepository.countByDeviceId(id),
                            activeByDevice.containsKey(id) ? 1 : 0,
                            orderToday > 0 ? revenueToday / orderToday : 0,
                            orderTotal > 0 ? revenueTotal / orderTotal : 0,
                            d.getRouteCode(),
                            d.getAddress(),
                            d.salesLockedEnabled(),
                            d.getSalesLockReason(),
                            d.getCurrentTempC(),
                            d.getFirmwareVersion()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportDeviceReportsCsv(Long userId) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        StringBuilder sb = new StringBuilder(
                "deviceId,deviceName,onlineStatus,routeCode,address,salesLocked,salesLockReason,currentTempC,firmwareVersion,orderTotal,revenueTotalCents,avgOrderValueTotalCents,orderToday,revenueTodayCents,avgOrderValueTodayCents,sessionTotal,sessionActive\n");
        for (MerchantDeviceReportDto r : deviceReports(userId)) {
            sb.append(csv(r.deviceId())).append(',')
                    .append(csv(r.deviceName())).append(',')
                    .append(csv(r.onlineStatus())).append(',')
                    .append(csv(r.routeCode())).append(',')
                    .append(csv(r.address())).append(',')
                    .append(r.salesLocked()).append(',')
                    .append(csv(r.salesLockReason())).append(',')
                    .append(r.currentTempC() == null ? "" : r.currentTempC()).append(',')
                    .append(csv(r.firmwareVersion())).append(',')
                    .append(r.orderTotal()).append(',')
                    .append(r.revenueTotalCents()).append(',')
                    .append(r.avgOrderValueTotalCents()).append(',')
                    .append(r.orderToday()).append(',')
                    .append(r.revenueTodayCents()).append(',')
                    .append(r.avgOrderValueTodayCents()).append(',')
                    .append(r.sessionTotal()).append(',')
                    .append(r.sessionActive()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<MerchantDeviceDto> buildDeviceDtos(List<DeviceInfo> devices) {
        Set<String> replenishing = replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(List.of(STATUS_IN_PROGRESS), 500).stream()
                .map(ReplenishmentTask::getDeviceId)
                .collect(Collectors.toSet());
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findByStateIn(ACTIVE_STATES, 2000).stream()
                .collect(Collectors.toMap(
                        ShoppingSession::getDeviceId,
                        s -> s,
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b
                ));
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> com.aicabinet.trade.support.MerchantNameSupport.resolve(
                                m.getMerchantId(), m.getMerchantName()),
                        (a, b) -> a));
        Map<String, DeviceSlotService.SlotStockCounts> stockByDevice = devices.stream()
                .collect(Collectors.toMap(
                        DeviceInfo::getDeviceId,
                        d -> deviceSlotService.countSlotStock(d.getDeviceId()),
                        (a, b) -> a));

        return devices.stream()
                .map(d -> toDeviceDto(
                        d,
                        activeByDevice.get(d.getDeviceId()),
                        replenishing.contains(d.getDeviceId()),
                        merchantNames,
                        stockByDevice.getOrDefault(d.getDeviceId(), DeviceSlotService.SlotStockCounts.EMPTY)))
                .toList();
    }

    private MerchantDto toMerchantDto(Merchant m, long deviceCount) {
        return new MerchantDto(
                m.getMerchantId(), m.getMerchantName(), m.getContactPhone(),
                m.getAlertContactName(), m.getAlertContactPhone(),
                m.getPlatformRateBps(), m.getWechatReceiverId(), m.getStatus(),
                m.getRemark(), deviceCount,
                m.isAllowMerchantPricingEdit(),
                m.isPackFieldEnabled(), m.isPackBizEnabled(), m.isPackTeamEnabled(),
                m.getParentMerchantId(),
                m.getCreatedAt(), m.getUpdatedAt()
        );
    }

    private MerchantDeviceDto toDeviceDto(DeviceInfo d, ShoppingSession active,
                                       boolean replenishmentInProgress,
                                       Map<String, String> merchantNames,
                                       DeviceSlotService.SlotStockCounts stock) {
        DeviceSlotService.SlotStockCounts counts =
                stock != null ? stock : DeviceSlotService.SlotStockCounts.EMPTY;
        return new MerchantDeviceDto(
                d.getDeviceId(), DeviceNameSupport.resolve(d.getDeviceId(), d.getDeviceName()), d.getDeviceType(), d.getOnlineStatus(),
                d.getMerchantId(),
                d.getMerchantId() != null ? merchantNames.get(d.getMerchantId()) : null,
                active != null ? active.getSessionId() : null,
                active != null ? active.getState().name() : null,
                d.getUpdatedAt(), replenishmentInProgress,
                d.salesLockedEnabled(),
                d.getAddress(),
                d.getRouteCode(),
                d.getCurrentTempC(),
                d.getTargetTempC(),
                d.getLifecycleStatus(),
                counts.lowStockSlotCount(),
                counts.oosSlotCount(),
                d.getSalesLockReason(),
                d.getLatitude(),
                d.getLongitude(),
                d.getFirmwareVersion(),
                counts.fillRatePct(),
                counts.lastCheckAt(),
                counts.lastRestockAt()
        );
    }

    private MerchantDeviceSettingsDto toDeviceSettings(DeviceInfo d) {
        return toDeviceSettings(d, null, null);
    }

    private MerchantDeviceSettingsDto toDeviceSettings(DeviceInfo d, Boolean tempCommandSent, String tempCommandMessage) {
        return new MerchantDeviceSettingsDto(
                d.getDeviceId(), DeviceNameSupport.resolve(d.getDeviceId(), d.getDeviceName()), d.getDeviceType(), d.getOnlineStatus(),
                d.getAddress(), d.getAlertContactName(), d.getAlertContactPhone(),
                d.getTargetTempC(), d.getCurrentTempC(), d.getTempReportedAt(),
                isTempOutOfRange(d), d.getOpsRemark(), tempCommandSent, tempCommandMessage,
                d.salesLockedEnabled(),
                d.getRouteCode(),
                d.getLifecycleStatus(),
                d.getSalesLockReason(),
                d.getLatitude(),
                d.getLongitude(),
                d.getFirmwareVersion()
        );
    }

    static boolean isTempOutOfRange(DeviceInfo d) {
        if (d.getTargetTempC() == null || d.getCurrentTempC() == null) {
            return false;
        }
        return Math.abs(d.getCurrentTempC() - d.getTargetTempC()) > 3;
    }

    private <T> T runWithDeviceSettingsLock(String deviceId, Supplier<T> action) {
        String lockKey = DeviceAssetService.deviceAssetLockKey(deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备设置处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    private static String csv(String value) {
        // S3：统一走 CsvCells（原实现已有公式中和，补齐 \t\r 前缀覆盖）
        return com.aicabinet.trade.support.CsvCells.escape(value);
    }
}
