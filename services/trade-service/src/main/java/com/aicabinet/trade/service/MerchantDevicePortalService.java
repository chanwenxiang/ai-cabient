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
    private final MerchantSelfServiceGate merchantSelfServiceGate;

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
                                       MerchantSelfServiceGate merchantSelfServiceGate) {
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
        this.merchantSelfServiceGate = merchantSelfServiceGate;
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

    @Transactional
    public MerchantDeviceSettingsDto updateDeviceSettings(Long userId, String deviceId,
                                                          UpdateMerchantDeviceSettingsRequest request) {
        permissionService.requirePermission(userId, "merchant:devices:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantFeaturePackService.requireDevicePack(userId, deviceId, MerchantFeaturePacks.FIELD);
        return runWithDeviceSettingsLock(deviceId, () -> doUpdateDeviceSettings(userId, deviceId, request));
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
        return temperatureReadingRepository.findByDeviceIdSince(deviceId, since).stream()
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

        return devices.stream()
                .map(d -> toDeviceDto(d, activeByDevice.get(d.getDeviceId()),
                        replenishing.contains(d.getDeviceId()), merchantNames))
                .toList();
    }

    private MerchantDto toMerchantDto(Merchant m, long deviceCount) {
        return new MerchantDto(
                m.getMerchantId(), m.getMerchantName(), m.getContactPhone(),
                m.getAlertContactName(), m.getAlertContactPhone(),
                m.getPlatformRateBps(), m.getWechatReceiverId(), m.getStatus(),
                m.getRemark(), deviceCount,
                m.isAllowMerchantPlanogramEdit(), m.isAllowMerchantPricingEdit(),
                m.isPackFieldEnabled(), m.isPackBizEnabled(), m.isPackTeamEnabled(),
                m.getParentMerchantId(),
                m.getCreatedAt(), m.getUpdatedAt()
        );
    }

    private MerchantDeviceDto toDeviceDto(DeviceInfo d, ShoppingSession active,
                                       boolean replenishmentInProgress,
                                       Map<String, String> merchantNames) {
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
                null,
                null,
                d.getSalesLockReason(),
                d.getLatitude(),
                d.getLongitude(),
                d.getFirmwareVersion()
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

    @Transactional(readOnly = true)
    public List<DeviceSlotDto> listDeviceSlots(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:slots:view");
        merchantPortalGuard.requireAccess(userId);
        return deviceSlotService.listSlots(userId, deviceId);
    }

    @Transactional
    public List<DeviceSlotDto> upsertDeviceSlots(Long userId, String deviceId,
                                                 List<UpsertDeviceSlotRequest> body) {
        permissionService.requirePermission(userId, "merchant:slots:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantSelfServiceGate.requirePlanogramEdit(userId, deviceId);
        return deviceSlotService.upsertSlots(userId, deviceId, body);
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
        if (value == null) return "";
        if (value.matches("^[=+\\-@].*")) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
