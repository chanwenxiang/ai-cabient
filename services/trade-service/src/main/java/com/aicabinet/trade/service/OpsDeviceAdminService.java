package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminDeviceDto;
import com.aicabinet.common.dto.AdminDeviceReportDto;
import com.aicabinet.common.dto.DeviceMapPointDto;
import com.aicabinet.common.dto.DeviceRefDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.UpdateDeviceRequest;
import com.aicabinet.common.dto.UpsertDeviceRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运营设备管理（原 AdminDashboardService 设备簇）。
 * Pass 3F PR-D：从神类拆出，门面仍可委托本类。
 */
@Service
public class OpsDeviceAdminService {

    private static final String PERM_OPS_DEVICE_LIST = "ops:device:list";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String DEPLOYED = "DEPLOYED";
    private static final String INBOUND = "INBOUND";
    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );

    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final MerchantMapper merchantRepository;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final CabinetOrderMapper orderRepository;
    private final DeviceIdService deviceIdService;
    private final DeviceIdRenameService deviceIdRenameService;
    private final DeviceSlotService deviceSlotService;
    private final AdminAuditService auditService;
    private final RefundPolicyService refundPolicyService;

    public OpsDeviceAdminService(PermissionService permissionService,
                                 MerchantScopeService merchantScopeService,
                                 DeviceInfoMapper deviceRepository,
                                 ShoppingSessionMapper sessionRepository,
                                 MerchantMapper merchantRepository,
                                 ReplenishmentTaskMapper replenishmentTaskRepository,
                                 CabinetOrderMapper orderRepository,
                                 DeviceIdService deviceIdService,
                                 DeviceIdRenameService deviceIdRenameService,
                                 DeviceSlotService deviceSlotService,
                                 AdminAuditService auditService,
                                 RefundPolicyService refundPolicyService) {
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.merchantRepository = merchantRepository;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.orderRepository = orderRepository;
        this.deviceIdService = deviceIdService;
        this.deviceIdRenameService = deviceIdRenameService;
        this.deviceSlotService = deviceSlotService;
        this.auditService = auditService;
        this.refundPolicyService = refundPolicyService;
    }

    @Transactional(readOnly = true)
    public List<DeviceRefDto> listDeviceRefs(Long operatorId) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DEVICE_LIST, "ops:device:ref");
        return merchantScopeService.allowedDevices(operatorId).stream()
                .sorted(Comparator.comparing(DeviceInfo::getDeviceId))
                .limit(500)
                .map(d -> new DeviceRefDto(d.getDeviceId(), d.getDeviceName(), d.getOnlineStatus(), d.getMerchantId()))
                .toList();
    }

    public List<AdminDeviceDto> listDevices(Long operatorId) {
        return listDevicesPaged(operatorId, 0, 5000, null, null).items();
    }

    @Transactional(readOnly = true)
    public AdminDeviceDto getDevice(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        DeviceInfo d = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        return toDeviceDto(d, findSessionForDeviceList(deviceId), replenishingDeviceIds().contains(deviceId));
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                       String q, String online) {
        return listDevicesPaged(operatorId, new DeviceListQuery(page, size, q, online, null, null, null, null));
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                       String q, String online, Boolean salesLocked) {
        return listDevicesPaged(operatorId,
                new DeviceListQuery(page, size, q, online, salesLocked, null, null, null));
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, DeviceListQuery query) {
        permissionService.requirePermission(operatorId, PERM_OPS_DEVICE_LIST);
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId);
        Set<String> replenishing = replenishingDeviceIds();
        Map<String, ShoppingSession> sessionByDevice = sessionRepository.findByStateIn(ACTIVE_STATES, 2000).stream()
                .collect(Collectors.toMap(
                        ShoppingSession::getDeviceId,
                        s -> s,
                        OpsDeviceAdminService::preferSessionForDeviceList
                ));

        DeviceListFilters filters = DeviceListFilters.from(
                query.q(), query.online(), query.salesLocked(),
                query.lifecycleStatus(), query.coopMode(), query.routeCode());
        List<AdminDeviceDto> filtered = devices.stream()
                .map(d -> toDeviceDto(d, sessionByDevice.get(d.getDeviceId()), replenishing.contains(d.getDeviceId())))
                .filter(d -> matchesDeviceListFilters(d, filters))
                .toList();

        int safeSize = Math.min(Math.max(query.size(), 1), 200);
        int safePage = Math.max(query.page(), 0);
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PageResult<>(filtered.subList(from, to), safePage, safeSize, filtered.size());
    }

    public record DeviceListQuery(int page, int size, String q, String online, Boolean salesLocked,
                                  String lifecycleStatus, String coopMode, String routeCode) {}

    record DeviceListFilters(String kw, String onlineFilter, Boolean salesLocked,
                             String lifeFilter, String coopFilter, String routeFilter) {
        static DeviceListFilters from(String q, String online, Boolean salesLocked,
                                      String lifecycleStatus, String coopMode, String routeCode) {
            return new DeviceListFilters(
                    q == null ? "" : q.trim().toLowerCase(),
                    online == null ? "" : online.trim().toUpperCase(),
                    salesLocked,
                    lifecycleStatus == null ? "" : lifecycleStatus.trim().toUpperCase(),
                    coopMode == null ? "" : coopMode.trim().toUpperCase(),
                    routeCode == null ? "" : routeCode.trim().toLowerCase()
            );
        }
    }

    static boolean matchesDeviceListFilters(AdminDeviceDto device, DeviceListFilters filters) {
        if (!filters.onlineFilter().isEmpty()
                && !filters.onlineFilter().equalsIgnoreCase(String.valueOf(device.onlineStatus()))) {
            return false;
        }
        if (filters.salesLocked() != null && device.salesLocked() != filters.salesLocked()) {
            return false;
        }
        if (!filters.lifeFilter().isEmpty()
                && !filters.lifeFilter().equalsIgnoreCase(
                String.valueOf(device.lifecycleStatus() == null ? DEPLOYED : device.lifecycleStatus()))) {
            return false;
        }
        if (!filters.coopFilter().isEmpty()
                && !filters.coopFilter().equalsIgnoreCase(
                String.valueOf(device.coopMode() == null ? "" : device.coopMode()))) {
            return false;
        }
        if (!filters.routeFilter().isEmpty()
                && !String.valueOf(device.routeCode() == null ? "" : device.routeCode())
                .toLowerCase().contains(filters.routeFilter())) {
            return false;
        }
        return matchesDeviceKeyword(device, filters.kw());
    }

    private static boolean matchesDeviceKeyword(AdminDeviceDto device, String kw) {
        if (kw.isEmpty()) {
            return true;
        }
        return containsIgnoreCase(device.deviceId(), kw)
                || containsIgnoreCase(device.deviceName(), kw)
                || containsIgnoreCase(device.merchantId(), kw)
                || containsIgnoreCase(device.merchantName(), kw)
                || containsIgnoreCase(device.imei(), kw)
                || containsIgnoreCase(device.assetOwner(), kw)
                || containsIgnoreCase(device.opsTags(), kw)
                || containsIgnoreCase(device.routeCode(), kw);
    }

    private static boolean containsIgnoreCase(Object value, String kw) {
        return String.valueOf(value == null ? "" : value).toLowerCase().contains(kw);
    }

    private static ShoppingSession preferSessionForDeviceList(ShoppingSession a, ShoppingSession b) {
        boolean aActive = ACTIVE_STATES.contains(a.getState());
        boolean bActive = ACTIVE_STATES.contains(b.getState());
        if (aActive != bActive) {
            return aActive ? a : b;
        }
        Instant au = sessionTouchTime(a);
        Instant bu = sessionTouchTime(b);
        return au.isAfter(bu) ? a : b;
    }

    private static Instant sessionTouchTime(ShoppingSession s) {
        if (s.getUpdatedAt() != null) {
            return s.getUpdatedAt();
        }
        return s.getCreatedAt() != null ? s.getCreatedAt() : Instant.EPOCH;
    }

    public PageResult<AdminDeviceReportDto> deviceReports(
            Long operatorId,
            int page,
            int size,
            String keyword,
            String online,
            String deviceId) {
        permissionService.requirePermission(operatorId, "ops:report:device");
        Instant todayStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findByStateIn(ACTIVE_STATES, 2000).stream()
                .collect(Collectors.toMap(ShoppingSession::getDeviceId, s -> s, (a, b) -> a));

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        String onlineNorm = online == null || online.isBlank() ? null : online.trim().toUpperCase();
        String deviceFilter = deviceId == null || deviceId.isBlank() ? null : deviceId.trim();

        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> m.getMerchantName() == null ? "" : m.getMerchantName(),
                        (a, b) -> a));

        List<AdminDeviceReportDto> filtered = merchantScopeService.allowedDevices(operatorId).stream()
                .filter(d -> matchesDeviceReportFilter(d, deviceFilter, onlineNorm, kw))
                .map(d -> toDeviceReportDto(d, todayStart, activeByDevice, merchantNames))
                .sorted(Comparator.comparing(AdminDeviceReportDto::deviceId, Comparator.nullsLast(String::compareTo)))
                .toList();

        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        long total = filtered.size();
        int from = Math.min(p * s, filtered.size());
        int to = Math.min(from + s, filtered.size());
        return new PageResult<>(filtered.subList(from, to), p, s, total);
    }

    private static boolean matchesDeviceReportFilter(DeviceInfo d, String deviceFilter, String onlineNorm, String kw) {
        if (deviceFilter != null && !deviceFilter.equals(d.getDeviceId())) {
            return false;
        }
        if (onlineNorm != null
                && !onlineNorm.equalsIgnoreCase(d.getOnlineStatus() == null ? "" : d.getOnlineStatus())) {
            return false;
        }
        if (kw.isEmpty()) {
            return true;
        }
        String id = d.getDeviceId() == null ? "" : d.getDeviceId().toLowerCase();
        String name = d.getDeviceName() == null ? "" : d.getDeviceName().toLowerCase();
        return id.contains(kw) || name.contains(kw);
    }

    private AdminDeviceReportDto toDeviceReportDto(
            DeviceInfo d,
            Instant todayStart,
            Map<String, ShoppingSession> activeByDevice,
            Map<String, String> merchantNames) {
        String id = d.getDeviceId();
        long orderTotal = orderRepository.countByDeviceId(id);
        long revenueTotal = orderRepository.sumAmountByDeviceId(id);
        long orderToday = orderRepository.countByDeviceIdAndCreatedAtAfter(id, todayStart);
        long revenueToday = orderRepository.sumAmountByDeviceIdSince(id, todayStart);
        String merchantId = d.getMerchantId();
        String merchantName = merchantId == null ? null : merchantNames.get(merchantId);
        if (merchantName != null && merchantName.isBlank()) {
            merchantName = null;
        }
        return new AdminDeviceReportDto(
                id,
                d.getDeviceName(),
                d.getOnlineStatus(),
                orderTotal,
                revenueTotal,
                orderToday,
                revenueToday,
                sessionRepository.countByDeviceId(id),
                activeByDevice.containsKey(id) ? 1 : 0,
                merchantId,
                merchantName,
                d.getRouteCode(),
                d.getAddress(),
                d.salesLockedEnabled(),
                d.getSalesLockReason(),
                d.getCurrentTempC(),
                d.getFirmwareVersion(),
                orderToday > 0 ? revenueToday / orderToday : 0,
                orderTotal > 0 ? revenueTotal / orderTotal : 0
        );
    }

    /** @deprecated 兼容旧调用：返回全量列表 */
    @Deprecated(since = "2026-08", forRemoval = false)
    @SuppressWarnings("java:S1133")
    public List<AdminDeviceReportDto> deviceReports(Long operatorId) {
        return deviceReports(operatorId, 0, 10_000, null, null, null).items();
    }

    @Transactional
    public AdminDeviceDto createDevice(Long operatorId, UpsertDeviceRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        String deviceId = deviceIdService.resolveForCreate(request.deviceId());
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(request.deviceName() != null ? request.deviceName().trim() : deviceId);
        device.setDeviceType(request.deviceType() != null && !request.deviceType().isBlank()
                ? request.deviceType().trim() : "AI_CABINET_V1");
        device.setOnlineStatus("OFFLINE");
        device.setSalesLocked(false);
        if (request.merchantId() != null && !request.merchantId().isBlank()) {
            String merchantId = request.merchantId().trim();
            requireMerchant(merchantId);
            merchantScopeService.requireMerchantAccess(operatorId, merchantId);
            device.setMerchantId(merchantId);
            device.setLifecycleStatus(DEPLOYED);
            device.setDeployedAt(Instant.now());
        } else {
            device.setLifecycleStatus(INBOUND);
        }
        deviceRepository.save(device);
        deviceSlotService.ensureDefaultSlots(deviceId, device.getDeviceType());
        auditService.appendLog(operatorId, "DEVICE_CREATE", "DEVICE", deviceId, device.getDeviceName());
        return toDeviceDto(device, null, false);
    }

    @Transactional
    public AdminDeviceDto resetHardwareBinding(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        DeviceInfo device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        device.setImei(null);
        device.setOnlineStatus("OFFLINE");
        device.setOnlineSince(null);
        deviceRepository.clearOnlineSince(deviceId);
        device.markHeartbeatReceived();
        deviceRepository.save(device);
        auditService.appendLog(operatorId, "DEVICE_RESET_HARDWARE", "DEVICE", deviceId, "cleared imei for rebind");
        return toDeviceDto(device, null, replenishingDeviceIds().contains(deviceId));
    }

    @Transactional
    public AdminDeviceDto regenerateDeviceId(Long operatorId, String oldDeviceId) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        DeviceInfo device = deviceRepository.findByIdForUpdate(oldDeviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, oldDeviceId);
        if (!INBOUND.equalsIgnoreCase(device.getLifecycleStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅入库状态设备可重新生成编号");
        }
        deviceIdRenameService.assertRenumberAllowed(oldDeviceId);
        String newDeviceId = deviceIdService.allocateRandomDeviceId();
        String remark = appendRenumberRemark(device.getLifecycleRemark(), oldDeviceId, newDeviceId);
        deviceIdRenameService.renameInPlace(oldDeviceId, newDeviceId);
        Instant now = Instant.now();
        deviceRepository.update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                .eq(DeviceInfo::getDeviceId, newDeviceId)
                .set(DeviceInfo::getLifecycleRemark, remark)
                .set(DeviceInfo::getUpdatedAt, now));
        DeviceInfo renamed = deviceRepository.findById(newDeviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "设备编号更新失败"));
        auditService.appendLog(operatorId, "DEVICE_REGENERATE_ID", "DEVICE", newDeviceId, "from=" + oldDeviceId);
        return toDeviceDto(renamed, null, false);
    }

    /** 编号更换备注追加（包可见，供单测）。 */
    static String appendRenumberRemark(String existing, String oldDeviceId, String newDeviceId) {
        String line = "编号已由 " + oldDeviceId + " 更换为 " + newDeviceId;
        if (existing == null || existing.isBlank()) {
            return line;
        }
        return existing.trim() + "；" + line;
    }

    @Transactional
    public AdminDeviceDto updateDevice(Long operatorId, String deviceId, UpdateDeviceRequest request) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(operatorId, deviceId);
        RefundPolicyPatch refundPatch = applyUpdateDeviceFields(operatorId, device, request);
        Instant now = Instant.now();
        device.setUpdatedAt(now);
        deviceRepository.save(device);
        if (refundPatch.touch()) {
            deviceRepository.update(null, Wrappers.<DeviceInfo>lambdaUpdate()
                    .eq(DeviceInfo::getDeviceId, deviceId)
                    .set(DeviceInfo::getRefundPolicy, refundPatch.stored())
                    .set(DeviceInfo::getUpdatedAt, now));
            device.setRefundPolicy(refundPatch.stored());
            device.setUpdatedAt(now);
        }
        auditService.appendLog(operatorId, "DEVICE_UPDATE", "DEVICE", deviceId,
                device.getDeviceName() + "; refundPolicy=" + device.getRefundPolicy());
        DeviceInfo fresh = deviceRepository.findById(deviceId).orElse(device);
        ShoppingSession session = findSessionForDeviceList(deviceId);
        return toDeviceDto(fresh, session, replenishingDeviceIds().contains(fresh.getDeviceId()));
    }

    private record RefundPolicyPatch(boolean touch, String stored) {}

    private RefundPolicyPatch applyUpdateDeviceFields(Long operatorId, DeviceInfo device, UpdateDeviceRequest request) {
        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            device.setDeviceName(request.deviceName().trim());
        }
        if (request.deviceType() != null && !request.deviceType().isBlank()) {
            device.setDeviceType(request.deviceType().trim());
        }
        applyUpdateDeviceMerchant(operatorId, device, request.merchantId());
        boolean touchRefundPolicy = request.refundPolicy() != null;
        String storedRefundPolicy = null;
        if (touchRefundPolicy) {
            storedRefundPolicy = RefundPolicyService.normalizeStored(request.refundPolicy());
            device.setRefundPolicy(storedRefundPolicy);
        }
        applyUpdateDeviceOptionalFields(device, request);
        return new RefundPolicyPatch(touchRefundPolicy, storedRefundPolicy);
    }

    private void applyUpdateDeviceMerchant(Long operatorId, DeviceInfo device, String merchantId) {
        if (merchantId == null) {
            return;
        }
        // 归属变更统一走生命周期 BIND/UNBIND，避免旁路绕过状态机
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "请通过设备生命周期「绑定商户 / 解绑」变更归属，勿直接改 merchantId");
    }

    private static void applyUpdateDeviceOptionalFields(DeviceInfo device, UpdateDeviceRequest request) {
        if (request.imei() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "IMEI 仅能通过柜机心跳自动绑定，请使用「解绑硬件」后由柜机重新上报");
        }
        if (request.assetOwner() != null) {
            device.setAssetOwner(trimToNull(request.assetOwner()));
        }
        if (request.coopMode() != null) {
            device.setCoopMode(DeviceAssetService.normalizeCoop(request.coopMode()));
        }
        if (request.depositCents() != null) {
            device.setDepositCents(request.depositCents() < 0 ? 0L : request.depositCents());
        }
        if (request.dataFeeCents() != null) {
            device.setDataFeeCents(request.dataFeeCents() < 0 ? 0L : request.dataFeeCents());
        }
        if (request.opsTags() != null) {
            device.setOpsTags(trimToNull(request.opsTags()));
        }
        if (request.routeCode() != null) {
            device.setRouteCode(trimToNull(request.routeCode()));
        }
        if (request.lifecycleRemark() != null) {
            device.setLifecycleRemark(trimToNull(request.lifecycleRemark()));
        }
        if (request.latitude() != null) {
            device.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            device.setLongitude(request.longitude());
        }
        if (request.address() != null) {
            device.setAddress(trimToNull(request.address()));
        }
    }

    @Transactional(readOnly = true)
    public List<DeviceMapPointDto> listDeviceMapPoints(
            Long operatorId, String lifecycleStatus, String routeCode, String online) {
        permissionService.requireAnyPermission(operatorId, PERM_OPS_DEVICE_LIST, "ops:device-map:view");
        String life = lifecycleStatus == null || lifecycleStatus.isBlank() ? DEPLOYED : lifecycleStatus.trim().toUpperCase();
        String route = routeCode == null ? "" : routeCode.trim();
        String onlineFilter = online == null ? "" : online.trim().toUpperCase();
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(operatorId);
        return devices.stream()
                .filter(d -> d.getLatitude() != null && d.getLongitude() != null)
                .filter(d -> "ALL".equals(life)
                        || life.equalsIgnoreCase(DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus())))
                .filter(d -> route.isEmpty() || route.equalsIgnoreCase(String.valueOf(d.getRouteCode())))
                .filter(d -> onlineFilter.isEmpty()
                        || onlineFilter.equalsIgnoreCase(String.valueOf(d.getOnlineStatus())))
                .map(d -> new DeviceMapPointDto(
                        d.getDeviceId(),
                        d.getDeviceName(),
                        d.getMerchantId(),
                        d.getOnlineStatus(),
                        DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus()),
                        d.getRouteCode(),
                        d.salesLockedEnabled(),
                        d.getLatitude(),
                        d.getLongitude(),
                        d.getAddress(),
                        d.getCoopMode()
                ))
                .toList();
    }

    private Set<String> replenishingDeviceIds() {
        return replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(List.of(STATUS_IN_PROGRESS), 500).stream()
                .map(ReplenishmentTask::getDeviceId)
                .collect(Collectors.toSet());
    }

    private ShoppingSession findActiveSession(String deviceId) {
        return sessionRepository.findByDeviceIdAndStateIn(deviceId, ACTIVE_STATES).stream()
                .findFirst().orElse(null);
    }

    private ShoppingSession findSessionForDeviceList(String deviceId) {
        ShoppingSession active = findActiveSession(deviceId);
        if (active != null) {
            return active;
        }
        return sessionRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private AdminDeviceDto toDeviceDto(DeviceInfo d, ShoppingSession active, boolean replenishmentInProgress) {
        String merchantName = null;
        if (d.getMerchantId() != null) {
            merchantName = merchantRepository.findById(d.getMerchantId())
                    .map(Merchant::getMerchantName)
                    .orElse(null);
        }
        return new AdminDeviceDto(
                d.getDeviceId(),
                d.getDeviceName(),
                d.getDeviceType(),
                d.getOnlineStatus(),
                d.getMerchantId(),
                merchantName,
                active != null ? active.getSessionId() : null,
                active != null ? active.getState().name() : null,
                d.getUpdatedAt(),
                replenishmentInProgress,
                d.getRefundPolicy(),
                refundPolicyService.resolveForDevice(d.getDeviceId()).name(),
                d.salesLockedEnabled(),
                DeviceAssetService.normalizeLifecycle(d.getLifecycleStatus()),
                d.getImei(),
                d.getAssetOwner(),
                d.getCoopMode(),
                d.getDepositCents(),
                d.getDataFeeCents(),
                d.getOpsTags(),
                d.getRouteCode(),
                d.getDeployedAt(),
                d.getLifecycleRemark(),
                d.getLatitude(),
                d.getLongitude(),
                d.getAddress(),
                d.getId(),
                d.getCurrentTempC(),
                d.getTargetTempC(),
                d.getFirmwareVersion(),
                d.getSalesLockReason()
        );
    }

    private void requireMerchant(String merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
