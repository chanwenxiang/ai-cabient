package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DeviceNameSupport;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MerchantPortalService {

    private static final List<SessionState> ACTIVE_STATES = List.of(
            SessionState.CREATED, SessionState.OPENING, SessionState.SHOPPING,
            SessionState.RECOGNIZING, SessionState.WAITING_UPLOAD, SessionState.SETTLING
    );
    private static final List<String> PENDING_SPLIT_STATUSES = List.of(
            "PENDING", "ACCRUED", "LEDGER_ONLY", "WECHAT_SUBMITTED", "SUBMITTED"
    );
    private static final List<String> FAILED_SPLIT_STATUSES = List.of("WECHAT_FAILED", "FAILED");
    private static final int WORKBENCH_ITEM_CAP = 50;
    private static final int EXPORT_LIMIT = 5000;
    private static final long MERCHANT_ROLE_ID = 6L;
    private static final long MERCHANT_STAFF_ROLE_ID = 7L;
    private static final long MERCHANT_FINANCE_ROLE_ID = 8L;
    private static final long MERCHANT_STORE_MANAGER_ROLE_ID = 10L;
    private static final long MERCHANT_REPLENISHER_ROLE_ID = 11L;
    private static final Set<String> MERCHANT_TEAM_ROLE_KEYS = Set.of(
            "merchant", "merchant_admin", "merchant_staff", "merchant_finance",
            "merchant_store_manager", "merchant_replenisher"
    );

    private final PermissionService permissionService;
    private final MerchantFinanceService merchantFinanceService;
    private final MerchantScopeService merchantScopeService;
    private final MerchantPortalGuard merchantPortalGuard;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final OpsUserMerchantMapper userMerchantRepository;
    private final OpsUserRoleMapper userRoleRepository;
    private final OpsRoleMapper roleRepository;
    private final OpsPermissionMapper permissionRepository;
    private final MerchantMapper merchantRepository;
    private final DeviceInfoMapper deviceRepository;
    private final CabinetOrderMapper orderRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final ReplenishmentTaskLineMapper replenishmentTaskLineRepository;
    private final DisputeTicketMapper disputeRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final DeviceSlotService deviceSlotService;
    private final AdminAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final DeviceTemperatureReadingMapper temperatureReadingRepository;
    private final DeviceServiceClient deviceServiceClient;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final OperatorUserIdAllocator operatorUserIdAllocator;
    private final MerchantSelfServiceGate merchantSelfServiceGate;
    private final MerchantFeaturePackService merchantFeaturePackService;

    public MerchantPortalService(MerchantFinanceService merchantFinanceService,
                                 PermissionService permissionService,
                                 MerchantScopeService merchantScopeService,
                                 MerchantPortalGuard merchantPortalGuard,
                                 UserInfoMapper userInfoRepository,
                                 UserAccountMapper userAccountRepository,
                                 OpsUserMerchantMapper userMerchantRepository,
                                 OpsUserRoleMapper userRoleRepository,
                                 OpsRoleMapper roleRepository,
                                 OpsPermissionMapper permissionRepository,
                                 MerchantMapper merchantRepository,
                                 DeviceInfoMapper deviceRepository,
                                 CabinetOrderMapper orderRepository,
                                 OrderRevenueSplitMapper splitRepository,
                                 ShoppingSessionMapper sessionRepository,
                                 ReplenishmentTaskMapper replenishmentTaskRepository,
                                 ReplenishmentTaskLineMapper replenishmentTaskLineRepository,
                                 DisputeTicketMapper disputeRepository,
                                 DeviceSkuInventoryMapper inventoryRepository,
                                 PullOffTaskMapper pullOffTaskRepository,
                                 DeviceSlotService deviceSlotService,
                                 AdminAuditService auditService,
                                 PasswordEncoder passwordEncoder,
                                 DeviceTemperatureReadingMapper temperatureReadingRepository,
                                 DeviceServiceClient deviceServiceClient,
                                 WeChatProfitSharingService profitSharingService,
                                 ProfitSharingProperties profitSharingProperties,
                                 WeChatPayProperties weChatPayProperties,
                                 OperatorUserIdAllocator operatorUserIdAllocator,
                                 MerchantSelfServiceGate merchantSelfServiceGate,
                                 MerchantFeaturePackService merchantFeaturePackService) {
        this.merchantFinanceService = merchantFinanceService;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.merchantPortalGuard = merchantPortalGuard;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.userMerchantRepository = userMerchantRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.merchantRepository = merchantRepository;
        this.deviceRepository = deviceRepository;
        this.orderRepository = orderRepository;
        this.splitRepository = splitRepository;
        this.sessionRepository = sessionRepository;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.replenishmentTaskLineRepository = replenishmentTaskLineRepository;
        this.disputeRepository = disputeRepository;
        this.inventoryRepository = inventoryRepository;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.deviceSlotService = deviceSlotService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.temperatureReadingRepository = temperatureReadingRepository;
        this.deviceServiceClient = deviceServiceClient;
        this.profitSharingService = profitSharingService;
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.operatorUserIdAllocator = operatorUserIdAllocator;
        this.merchantSelfServiceGate = merchantSelfServiceGate;
        this.merchantFeaturePackService = merchantFeaturePackService;
    }

    @Transactional(readOnly = true)
    public MerchantMeDto getMe(Long userId) {
        merchantPortalGuard.requireAccess(userId);
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST));
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        Map<String, Long> deviceCounts = deviceRepository.findByMerchantIdIn(allowed).stream()
                .collect(Collectors.groupingBy(DeviceInfo::getMerchantId, Collectors.counting()));
        List<MerchantDto> merchants = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .map(m -> toMerchantDto(m, deviceCounts.getOrDefault(m.getMerchantId(), 0L)))
                .toList();
        List<String> permissions = merchantFeaturePackService.filterPermissions(
                userId,
                permissionRepository.findPermCodesByUserId(userId).stream()
                        .filter(p -> p.startsWith("merchant:"))
                        .sorted()
                        .toList());
        boolean canEditPricing = merchants.stream().anyMatch(MerchantDto::allowMerchantPricingEdit);
        List<String> enabledPacks = merchantFeaturePackService.enabledPacksList(userId);
        return new MerchantMeDto(
                user.getUserId(), user.getPhoneNumber(), user.getName(),
                merchants, permissions, canEditPricing, enabledPacks);
    }

    @Transactional(readOnly = true)
    public MerchantDashboardStatsDto getStats(Long userId) {
        merchantPortalGuard.requireAccess(userId);
        List<DeviceInfo> devices = merchantFeaturePackService.allowedDevicesForPack(
                userId, MerchantFeaturePacks.FIELD);
        int online = (int) devices.stream().filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus())).count();
        int offline = devices.size() - online;

        boolean canFinanceKpi = permissionService.hasAnyPermission(
                userId,
                "merchant:reports:view",
                "merchant:settlements:view",
                "merchant:trend:view",
                "merchant:analytics:view");
        if (!canFinanceKpi) {
            return new MerchantDashboardStatsDto(
                    devices.size(), online, offline, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();

        Set<String> bizDeviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (bizDeviceIds == null) {
            bizDeviceIds = deviceRepository.findAll().stream()
                    .map(DeviceInfo::getDeviceId)
                    .collect(Collectors.toSet());
        }
        long ordersToday = bizDeviceIds.isEmpty() ? 0
                : orderRepository.countByDeviceIdInAndCreatedAtAfter(bizDeviceIds, startOfDay);
        long revenueToday = bizDeviceIds.isEmpty() ? 0
                : orderRepository.sumTotalAmountByDeviceIdInSince(bizDeviceIds, startOfDay);

        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null) {
            merchantIds = Set.of();
        }
        long incomeToday = merchantIds.isEmpty() ? 0
                : splitRepository.sumMerchantCentsByMerchantIdInSince(merchantIds, startOfDay);
        long incomeTotal = merchantIds.isEmpty() ? 0
                : splitRepository.sumMerchantCentsByMerchantIdIn(merchantIds);
        long pendingSplits = merchantIds.isEmpty() ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);
        long pendingAmount = merchantIds.isEmpty() ? 0
                : splitRepository.sumMerchantCentsByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);
        Instant startOfMonth = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        long settledMonth = merchantIds.isEmpty() ? 0
                : splitRepository.sumSuccessMerchantCentsByMerchantIdInSince(merchantIds, startOfMonth);
        long failedSplits = merchantIds.isEmpty() ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, FAILED_SPLIT_STATUSES);

        return new MerchantDashboardStatsDto(
                devices.size(), online, offline, ordersToday, revenueToday,
                incomeToday, incomeTotal, pendingSplits, pendingAmount, settledMonth, failedSplits
        );
    }

    @Transactional(readOnly = true)
    public MerchantTrendDto getTrend(Long userId, int days) {
        permissionService.requirePermission(userId, "merchant:trend:view");
        merchantPortalGuard.requireAccess(userId);
        int window = Math.min(Math.max(days, 1), 90);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(window - 1L);
        Instant since = start.atStartOfDay(zone).toInstant();

        Set<String> deviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);

        Map<LocalDate, long[]> orderBuckets = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            orderBuckets.put(start.plusDays(i), new long[]{0, 0});
        }
        if (deviceIds != null && !deviceIds.isEmpty()) {
            for (CabinetOrder order : orderRepository.findByDeviceIdInAndCreatedAtAfter(deviceIds, since)) {
                LocalDate day = order.getCreatedAt().atZone(zone).toLocalDate();
                long[] bucket = orderBuckets.get(day);
                if (bucket != null) {
                    bucket[0]++;
                    bucket[1] += order.getTotalAmountCents();
                }
            }
        }

        Map<LocalDate, Long> incomeByDay = new LinkedHashMap<>();
        for (int i = 0; i < window; i++) {
            incomeByDay.put(start.plusDays(i), 0L);
        }
        if (merchantIds != null && !merchantIds.isEmpty()) {
            for (OrderRevenueSplit split : splitRepository.findByMerchantIdInAndCreatedAtAfter(merchantIds, since)) {
                LocalDate day = split.getCreatedAt().atZone(zone).toLocalDate();
                incomeByDay.merge(day, (long) split.getMerchantCents(), Long::sum);
            }
        }

        List<MerchantDailyTrendDto> trendDays = orderBuckets.entrySet().stream()
                .map(e -> new MerchantDailyTrendDto(
                        e.getKey().toString(),
                        e.getValue()[0],
                        e.getValue()[1],
                        incomeByDay.getOrDefault(e.getKey(), 0L)))
                .toList();
        return new MerchantTrendDto(trendDays);
    }

    @Transactional(readOnly = true)
    public MerchantWorkbenchDto getWorkbench(Long userId) {
        permissionService.requirePermission(userId, "merchant:alerts:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> deviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        Set<String> bizDeviceIds = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (deviceIds != null && deviceIds.isEmpty()
                && bizDeviceIds != null && bizDeviceIds.isEmpty()
                && merchantIds != null && merchantIds.isEmpty()) {
            return new MerchantWorkbenchDto(0, 0, 0, 0, 0, 0, List.of());
        }

        List<OpsActionItemDto> items = new ArrayList<>();

        long openDisputes = bizDeviceIds == null ? disputeRepository.countByStatus("OPEN")
                : (bizDeviceIds.isEmpty() ? 0 : disputeRepository.countOpenByDeviceIds(bizDeviceIds));
        disputeRepository.findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(bizDeviceIds, sessionDeviceId(d.getSessionId())))
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DISPUTE", "HIGH", "待审核争议",
                        formatDisputeReason(d.getReason()),
                        sessionDeviceId(d.getSessionId()), d.getSessionId(), d.getTicketId(),
                        null, null, d.getCreatedAt(), d.getSlaDueAt())));

        long offline = deviceIds == null
                ? deviceRepository.countByOnlineStatusNot("ONLINE")
                : (deviceIds.isEmpty() ? 0
                : deviceRepository.countByDeviceIdInAndOnlineStatusNot(deviceIds, "ONLINE"));
        deviceRepository.findByOnlineStatusNot("ONLINE", WORKBENCH_ITEM_CAP).stream()
                .filter(d -> inDeviceScope(deviceIds, d.getDeviceId()))
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DEVICE_OFFLINE", "HIGH", "柜机离线",
                        d.getDeviceName() != null ? d.getDeviceName() : d.getDeviceId(),
                        d.getDeviceId(), null, null, null, null, d.getUpdatedAt(), null)));

        long lowStock = deviceIds == null
                ? inventoryRepository.countLowStock()
                : (deviceIds.isEmpty() ? 0 : inventoryRepository.countLowStockByDeviceIds(deviceIds));
        inventoryRepository.findLowStockLimit(WORKBENCH_ITEM_CAP).stream()
                .filter(inv -> inDeviceScope(deviceIds, inv.getId().getDeviceId()))
                .forEach(inv -> items.add(new OpsActionItemDto(
                        "LOW_STOCK", "MEDIUM", "库存偏低",
                        "SKU " + inv.getId().getSkuId() + " 当前 " + inv.getQuantity()
                                + " / 阈值 " + inv.getLowThreshold(),
                        inv.getId().getDeviceId(), null, null, inv.getId().getSkuId(),
                        null, inv.getUpdatedAt(), null)));

        long expiry = deviceIds == null
                ? pullOffTaskRepository.countByStatus("OPEN")
                : (deviceIds.isEmpty() ? 0
                : pullOffTaskRepository.countByStatusAndDeviceIdIn("OPEN", deviceIds));
        pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN", WORKBENCH_ITEM_CAP).stream()
                .filter(task -> inDeviceScope(deviceIds, task.getDeviceId()))
                .forEach(task -> items.add(new OpsActionItemDto(
                        "EXPIRY", "MEDIUM", "临期/过期下架",
                        "SKU " + task.getSkuId() + " · " + task.getReason(),
                        task.getDeviceId(), null, null, task.getSkuId(),
                        task.getTaskId(), task.getCreatedAt(), null)));

        List<SlotDiscrepancyAlertDto> discrepancies = deviceSlotService.listDiscrepancyAlerts(userId, null).stream()
                .filter(a -> inDeviceScope(deviceIds, a.deviceId()))
                .limit(WORKBENCH_ITEM_CAP)
                .toList();
        discrepancies.forEach(a -> items.add(new OpsActionItemDto(
                "SLOT_DISCREPANCY", "MEDIUM", "货道账实差异",
                a.slotCode() + " 账面 " + a.bookQty() + " 实测 " + a.physicalQty(),
                a.deviceId(), null, null, a.assignedSkuId(),
                null, a.lastPhysicalAt(), null)));

        replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(
                        List.of("PENDING", "IN_PROGRESS"), WORKBENCH_ITEM_CAP).stream()
                .filter(t -> inDeviceScope(deviceIds, t.getDeviceId()))
                .forEach(t -> items.add(new OpsActionItemDto(
                        "REPLENISHMENT", "MEDIUM", "补货任务进行中",
                        "状态 " + t.getStatus() + (t.getNotes() != null ? " · " + t.getNotes() : ""),
                        t.getDeviceId(), null, null, null, t.getTaskId(), t.getCreatedAt(), null)));

        long pendingSplits = merchantIds == null ? 0
                : splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);

        items.sort(Comparator
                .comparing((OpsActionItemDto i) -> severityRank(i.severity())).reversed()
                .thenComparing(OpsActionItemDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return new MerchantWorkbenchDto(
                openDisputes, offline, lowStock, expiry,
                discrepancies.size(), pendingSplits,
                items.stream().limit(100).toList()
        );
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
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));

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
        deviceRepository.save(device);
        Boolean tempCommandSent = null;
        String tempCommandMessage = null;
        if (request.targetTempC() != null) {
            if ("ONLINE".equalsIgnoreCase(device.getOnlineStatus())) {
                try {
                    deviceServiceClient.requestSetTargetTemp(deviceId, request.targetTempC());
                    tempCommandSent = true;
                    tempCommandMessage = "已向柜机下发目标温度 " + request.targetTempC() + "°C";
                } catch (Exception ex) {
                    tempCommandSent = false;
                    tempCommandMessage = "设置已保存，柜机指令下发失败（请确认 device-service 在线）";
                }
            } else {
                tempCommandSent = false;
                tempCommandMessage = "设置已保存，柜机离线时将在上线后手动同步";
            }
        }
        auditService.record(userId, "MERCHANT_DEVICE_SETTINGS", "DEVICE", deviceId,
                "名称：" + device.getDeviceName());
        return toDeviceSettings(device, tempCommandSent, tempCommandMessage);
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
                    return new MerchantDeviceReportDto(
                            id, d.getDeviceName(), d.getOnlineStatus(),
                            orderRepository.countByDeviceId(id),
                            orderRepository.sumAmountByDeviceId(id),
                            orderRepository.countByDeviceIdAndCreatedAtAfter(id, todayStart),
                            orderRepository.sumAmountByDeviceIdSince(id, todayStart),
                            sessionRepository.countByDeviceId(id),
                            activeByDevice.containsKey(id) ? 1 : 0
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantOrderSummaryDto> listOrders(Long userId, int page, int size, String deviceId) {
        return merchantFinanceService.listOrders(userId, page, size, deviceId, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long userId, String orderId) {
        return merchantFinanceService.getOrder(userId, orderId);
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantDisputeSummaryDto> listDisputes(Long userId, int page, int size,
                                                     String status, String deviceId) {
        permissionService.requirePermission(userId, "merchant:disputes:list");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Collection<String> deviceScope = merchantFeaturePackService.intersectDeviceFilterForPack(
                userId, deviceId, MerchantFeaturePacks.BIZ);
        Page<DisputeTicket> result;
        if (deviceScope != null && deviceScope.isEmpty()) {
            result = Page.empty(pageable);
        } else if (deviceScope != null) {
            result = disputeRepository.searchByDeviceIds(
                    blankToNull(status), null, deviceScope, null, null, null, pageable);
        } else {
            result = disputeRepository.search(blankToNull(status), null, blankToNull(deviceId), null, null, null, pageable);
        }
        return new PageResult<>(
                result.getContent().stream().map(this::toMerchantDisputeSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public List<DeviceInventoryDto> listInventory(Long userId, String deviceId, boolean lowStockOnly) {
        permissionService.requirePermission(userId, "merchant:inventory:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }

        List<DeviceSkuInventory> rows;
        if (deviceId != null && !deviceId.isBlank()) {
            String dev = deviceId.trim();
            if (allowed != null && !allowed.contains(dev)) {
                return List.of();
            }
            rows = inventoryRepository.findByIdDeviceId(dev);
            if (lowStockOnly) {
                rows = rows.stream()
                        .filter(i -> i.getQuantity() <= i.getLowThreshold())
                        .toList();
            }
        } else if (lowStockOnly) {
            rows = inventoryRepository.findLowStockLimit(500);
        } else if (allowed != null) {
            rows = inventoryRepository.findByIdDeviceIdIn(allowed);
        } else {
            rows = inventoryRepository.findAllLimit(2000);
        }

        return rows.stream()
                .filter(i -> inDeviceScope(allowed, i.getId().getDeviceId()))
                .map(i -> new DeviceInventoryDto(
                        i.getId().getDeviceId(), i.getId().getSkuId(),
                        i.getQuantity(), i.getCapacity(), i.getLowThreshold(), i.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PullOffTaskDto> listExpiryAlerts(Long userId) {
        permissionService.requirePermission(userId, "merchant:inventory:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        return pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN", 500).stream()
                .filter(t -> inDeviceScope(allowed, t.getDeviceId()))
                .map(t -> {
                    int headroom = 0;
                    try {
                        headroom = deviceSlotService.totalHeadroomForSku(t.getDeviceId(), t.getSkuId());
                    } catch (Exception ignored) {
                        headroom = 0;
                    }
                    return new PullOffTaskDto(
                            t.getTaskId(), t.getDeviceId(), t.getSkuId(), t.getLotId(),
                            t.getBatchNo(), t.getQuantity(), t.getReason(), t.getStatus(), t.getCreatedAt(),
                            Math.max(0, headroom));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SlotDiscrepancyAlertDto> listSlotDiscrepancies(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:inventory:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        return deviceSlotService.listDiscrepancyAlerts(userId, deviceId).stream()
                .filter(a -> inDeviceScope(allowed, a.deviceId()))
                .toList();
    }

    @Transactional
    public List<MerchantDto> updateProfile(Long userId, UpdateMerchantProfileRequest request) {
        permissionService.requirePermission(userId, "merchant:profile:edit");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.TEAM);
        if (allowed == null || allowed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
        List<Merchant> merchants = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .toList();
        for (Merchant m : merchants) {
            if (request.contactPhone() != null) {
                m.setContactPhone(blankToNull(request.contactPhone()));
            }
            if (request.alertContactName() != null) {
                m.setAlertContactName(blankToNull(request.alertContactName()));
            }
            if (request.alertContactPhone() != null) {
                m.setAlertContactPhone(blankToNull(request.alertContactPhone()));
            }
            merchantRepository.save(m);
        }
        auditService.record(userId, "MERCHANT_PROFILE_UPDATE", "MERCHANT",
                String.join(",", allowed), "profile updated");
        Map<String, Long> deviceCounts = deviceRepository.findByMerchantIdIn(allowed).stream()
                .collect(Collectors.groupingBy(DeviceInfo::getMerchantId, Collectors.counting()));
        return merchants.stream()
                .map(m -> toMerchantDto(m, deviceCounts.getOrDefault(m.getMerchantId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public MerchantSettlementOverviewDto getSettlementOverview(Long userId) {
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return new MerchantSettlementOverviewDto(0, 0, 0, 0, buildProfitSharingStatus(), List.of());
        }
        long pendingAmount = splitRepository.sumMerchantCentsByMerchantIdInAndStatusIn(
                merchantIds, PENDING_SPLIT_STATUSES);
        long pendingCount = splitRepository.countByMerchantIdInAndStatusIn(merchantIds, PENDING_SPLIT_STATUSES);
        Instant startOfMonth = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        long settledMonth = splitRepository.sumSuccessMerchantCentsByMerchantIdInSince(merchantIds, startOfMonth);
        long failedCount = splitRepository.countByMerchantIdInAndStatusIn(merchantIds, FAILED_SPLIT_STATUSES);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> merchantIds.contains(m.getMerchantId()))
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> com.aicabinet.trade.support.MerchantNameSupport.resolve(
                                m.getMerchantId(), m.getMerchantName()),
                        (a, b) -> a));
        List<RevenueSplitDto> recentFailures = splitRepository
                .findTop5ByMerchantIdInAndStatusInOrderByCreatedAtDesc(merchantIds, FAILED_SPLIT_STATUSES)
                .stream()
                .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                .toList();
        return new MerchantSettlementOverviewDto(
                pendingAmount, pendingCount, settledMonth, failedCount,
                buildProfitSharingStatus(), recentFailures);
    }

    @Transactional(readOnly = true)
    public List<MerchantDailySettlementDto> listDailySettlements(Long userId, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        Instant from = parseDateStart(fromDate != null ? fromDate : LocalDate.now().minusDays(30).toString());
        Instant to = parseDateEnd(toDate != null ? toDate : LocalDate.now().toString());
        return splitRepository.aggregateDailyByMerchants(merchantIds, from, to).stream()
                .map(this::toDailySettlement)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantSettlementBatchDto> listSettlementBatches(Long userId, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        Instant from = parseDateStart(fromDate != null ? fromDate : LocalDate.now().minusDays(90).toString());
        Instant to = parseDateEnd(toDate != null ? toDate : LocalDate.now().toString());
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> merchantIds.contains(m.getMerchantId()))
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> com.aicabinet.trade.support.MerchantNameSupport.resolve(
                                m.getMerchantId(), m.getMerchantName()),
                        (a, b) -> a));
        return splitRepository.aggregateBatchByMerchants(merchantIds, from, to).stream()
                .map(row -> toBatchSettlement(row, merchantNames))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevenueSplitDto> getSettlementBatchDetail(Long userId, String batchNo) {
        permissionService.requirePermission(userId, "merchant:settlements:view");
        merchantPortalGuard.requireAccess(userId);
        if (batchNo == null || batchNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "批次号不能为空");
        }
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.BIZ);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> merchantIds.contains(m.getMerchantId()))
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> com.aicabinet.trade.support.MerchantNameSupport.resolve(
                                m.getMerchantId(), m.getMerchantName()),
                        (a, b) -> a));
        return splitRepository.findByMerchantIdInAndSettlementBatchNoOrderByCreatedAtDesc(
                        merchantIds, batchNo.trim()).stream()
                .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] exportSettlementsCsv(Long userId, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:settlements:export");
        merchantPortalGuard.requireAccess(userId);
        List<MerchantDailySettlementDto> days = listDailySettlements(userId, fromDate, toDate);
        StringBuilder sb = new StringBuilder();
        sb.append("date,orderCount,grossCents,platformCents,merchantCents,settledCents,pendingCents,failedCount\n");
        for (MerchantDailySettlementDto d : days) {
            sb.append(d.date()).append(',')
                    .append(d.orderCount()).append(',')
                    .append(d.grossCents()).append(',')
                    .append(d.platformCents()).append(',')
                    .append(d.merchantCents()).append(',')
                    .append(d.settledCents()).append(',')
                    .append(d.pendingCents()).append(',')
                    .append(d.failedCount()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public PageResult<RevenueSplitDto> listSplits(Long userId, int page, int size,
                                                 String status, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:splits:list");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.BIZ);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Instant from = resolveSplitRangeStart(fromDate);
        Instant to = resolveSplitRangeEnd(toDate);
        String normalizedStatus = status != null && !status.isBlank() ? status.trim().toUpperCase() : null;

        Page<OrderRevenueSplit> result = splitRepository.searchByMerchants(
                allowed, normalizedStatus != null ? normalizedStatus : "", from, to, pageable);

        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> com.aicabinet.trade.support.MerchantNameSupport.resolve(
                                m.getMerchantId(), m.getMerchantName()),
                        (a, b) -> a));
        return new PageResult<>(
                result.getContent().stream()
                        .map(s -> toSplitDto(s, merchantNames.get(s.getMerchantId())))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long userId, String deviceId) {
        return merchantFinanceService.exportOrdersCsv(userId, deviceId);
    }

    @Transactional(readOnly = true)
    public byte[] exportSplitsCsv(Long userId, String status, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedMerchantIdsForPack(userId, MerchantFeaturePacks.BIZ);
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT);
        Page<OrderRevenueSplit> page = splitRepository.searchByMerchants(
                allowed, normalizedStatus(blankToNull(status)) != null
                        ? normalizedStatus(blankToNull(status)) : "",
                resolveSplitRangeStart(fromDate), resolveSplitRangeEnd(toDate), pageable);
        Map<String, String> merchantNames = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .collect(Collectors.toMap(
                        Merchant::getMerchantId,
                        m -> com.aicabinet.trade.support.MerchantNameSupport.resolve(
                                m.getMerchantId(), m.getMerchantName()),
                        (a, b) -> a));
        StringBuilder sb = new StringBuilder(
                "splitId,orderId,merchantId,merchantName,deviceId,grossCents,platformCents,merchantCents,status,createdAt\n");
        for (OrderRevenueSplit s : page.getContent()) {
            sb.append(csv(s.getSplitId())).append(',')
                    .append(csv(s.getOrderId())).append(',')
                    .append(csv(s.getMerchantId())).append(',')
                    .append(csv(merchantNames.get(s.getMerchantId()))).append(',')
                    .append(csv(s.getDeviceId())).append(',')
                    .append(s.getGrossCents()).append(',')
                    .append(s.getPlatformCents()).append(',')
                    .append(s.getMerchantCents()).append(',')
                    .append(csv(s.getStatus())).append(',')
                    .append(csv(String.valueOf(s.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportDeviceReportsCsv(Long userId) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        StringBuilder sb = new StringBuilder(
                "deviceId,deviceName,onlineStatus,orderTotal,revenueTotalCents,orderToday,revenueTodayCents,sessionTotal,sessionActive\n");
        for (MerchantDeviceReportDto r : deviceReports(userId)) {
            sb.append(csv(r.deviceId())).append(',')
                    .append(csv(r.deviceName())).append(',')
                    .append(csv(r.onlineStatus())).append(',')
                    .append(r.orderTotal()).append(',')
                    .append(r.revenueTotalCents()).append(',')
                    .append(r.orderToday()).append(',')
                    .append(r.revenueTodayCents()).append(',')
                    .append(r.sessionTotal()).append(',')
                    .append(r.sessionActive()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskDto> listReplenishmentTasks(Long userId, String status, String deviceId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantFeaturePackService.allowedDeviceIdsForPack(
                userId, MerchantFeaturePacks.FIELD);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantFeaturePackService.requireDevicePack(
                    userId, deviceId.trim(), MerchantFeaturePacks.FIELD);
        }
        List<String> statuses = status != null && !status.isBlank()
                ? List.of(status.trim().toUpperCase())
                : List.of("PENDING", "IN_PROGRESS", "COMPLETED");
        return replenishmentTaskRepository.findByStatusIn(statuses).stream()
                .filter(t -> inDeviceScope(allowed, t.getDeviceId()))
                .filter(t -> deviceId == null || deviceId.isBlank() || deviceId.trim().equals(t.getDeviceId()))
                .sorted(Comparator.comparing(ReplenishmentTask::getCreatedAt).reversed())
                .limit(100)
                .map(this::toReplenishmentTaskDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskLineDto> getReplenishmentTaskLines(Long userId, Long taskId) {
        permissionService.requirePermission(userId, "merchant:replenishment:view");
        merchantPortalGuard.requireAccess(userId);
        ReplenishmentTask task = replenishmentTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "补货任务不存在"));
        merchantFeaturePackService.requireDevicePack(
                userId, task.getDeviceId(), MerchantFeaturePacks.FIELD);
        return replenishmentTaskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).stream()
                .map(this::toReplenishmentLineDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantUserDto> listTeamUsers(Long userId) {
        permissionService.requirePermission(userId, "merchant:users:list");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.TEAM);
        if (merchants == null || merchants.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = userMerchantRepository.findByMerchantIdIn(merchants).stream()
                .map(m -> m.getId().getUserId())
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userInfoRepository.findByUserIdIn(new ArrayList<>(userIds)).stream()
                .sorted(Comparator.comparing(UserInfo::getUserId))
                .map(u -> toMerchantUserDto(u, u.getUserId().equals(userId)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantTeamRoleDto> listTeamRoles(Long userId) {
        permissionService.requireAnyPermission(userId, "merchant:users:invite", "merchant:users:edit");
        merchantPortalGuard.requireAccess(userId);
        return List.of(
                new MerchantTeamRoleDto("merchant", "商户管理员", "全量经营与团队管理"),
                new MerchantTeamRoleDto("merchant_store_manager", "店长", "现场+经营只读，可看团队"),
                new MerchantTeamRoleDto("merchant_finance", "财务", "结算对账与钱包只读"),
                new MerchantTeamRoleDto("merchant_replenisher", "补货员", "柜机补货与库存"),
                new MerchantTeamRoleDto("merchant_staff", "店员", "通用只读协同")
        );
    }

    @Transactional
    public MerchantUserDto updateTeamUser(Long operatorId, Long targetUserId, UpdateMerchantUserRequest request) {
        permissionService.requirePermission(operatorId, "merchant:users:edit");
        merchantPortalGuard.requireAccess(operatorId);
        UserInfo target = requireTeamMember(operatorId, targetUserId);
        if (request.displayName() != null && !request.displayName().isBlank()) {
            target.setName(request.displayName().trim());
        }
        if (request.roleKey() != null && !request.roleKey().isBlank()) {
            if (targetUserId.equals(operatorId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改自己的角色");
            }
            long roleId = resolveMerchantRoleId(request.roleKey());
            userRoleRepository.deleteByIdUserId(targetUserId);
            userRoleRepository.insert(new OpsUserRole(targetUserId, roleId));
        }
        userInfoRepository.save(target);
        auditService.record(operatorId, "MERCHANT_USER_UPDATE", "USER", String.valueOf(targetUserId),
                "role=" + request.roleKey() + ",name=" + request.displayName());
        return toMerchantUserDto(target, false);
    }

    @Transactional
    public MerchantUserDto disableTeamUser(Long operatorId, Long targetUserId) {
        permissionService.requirePermission(operatorId, "merchant:users:disable");
        merchantPortalGuard.requireAccess(operatorId);
        if (targetUserId.equals(operatorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用自己");
        }
        UserInfo target = requireTeamMember(operatorId, targetUserId);
        target.setStatus("INACTIVE");
        userInfoRepository.save(target);
        auditService.record(operatorId, "MERCHANT_USER_DISABLE", "USER", String.valueOf(targetUserId), null);
        return toMerchantUserDto(target, false);
    }

    @Transactional
    public MerchantUserDto enableTeamUser(Long operatorId, Long targetUserId) {
        permissionService.requirePermission(operatorId, "merchant:users:edit");
        merchantPortalGuard.requireAccess(operatorId);
        UserInfo target = requireTeamMember(operatorId, targetUserId);
        target.setStatus("ACTIVE");
        userInfoRepository.save(target);
        auditService.record(operatorId, "MERCHANT_USER_ENABLE", "USER", String.valueOf(targetUserId), null);
        return toMerchantUserDto(target, false);
    }

    @Transactional
    public MerchantUserDto resetTeamUserPassword(Long operatorId, Long targetUserId,
                                                 ResetMerchantUserPasswordRequest request) {
        permissionService.requirePermission(operatorId, "merchant:users:reset-password");
        merchantPortalGuard.requireAccess(operatorId);
        if (request == null || request.password() == null || request.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        UserInfo target = requireTeamMember(operatorId, targetUserId);
        target.setPasswordHash(passwordEncoder.encode(request.password()));
        userInfoRepository.save(target);
        auditService.record(operatorId, "MERCHANT_USER_RESET_PASSWORD", "USER", String.valueOf(targetUserId), null);
        return toMerchantUserDto(target, targetUserId.equals(operatorId));
    }

    @Transactional
    public MerchantUserDto createTeamUser(Long userId, CreateMerchantUserRequest request) {
        permissionService.requirePermission(userId, "merchant:users:invite");
        merchantPortalGuard.requireAccess(userId);
        if (request.phoneNumber() == null || request.phoneNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");
        }
        if (request.password() == null || request.password().length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
        String phone = request.phoneNumber().trim();
        if (userInfoRepository.findByPhoneNumber(phone).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已注册");
        }
        Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.TEAM);
        if (merchants == null || merchants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
        long newUserId = operatorUserIdAllocator.nextId();

        UserInfo user = new UserInfo();
        user.setUserId(newUserId);
        user.setPhoneNumber(phone);
        user.setName(request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim() : "商户成员");
        user.setVerified(true);
        user.setStatus("ACTIVE");
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userInfoRepository.save(user);

        UserAccount account = new UserAccount();
        account.setUserId(newUserId);
        account.setBalanceCents(0);
        userAccountRepository.save(account);

        long roleId = resolveMerchantRoleId(request.roleKey());
        userRoleRepository.insert(new OpsUserRole(newUserId, roleId));
        for (String merchantId : merchants) {
            userMerchantRepository.insert(new OpsUserMerchant(newUserId, merchantId));
        }
        auditService.record(userId, "MERCHANT_USER_CREATE", "USER", String.valueOf(newUserId),
                "phone=" + phone + ",role=" + request.roleKey());
        return toMerchantUserDto(user, false);
    }

    private UserInfo requireTeamMember(Long operatorId, Long targetUserId) {
        Set<String> merchants = merchantFeaturePackService.allowedMerchantIdsForPack(
                operatorId, MerchantFeaturePacks.TEAM);
        if (merchants == null || merchants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
        UserInfo target = userInfoRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在"));
        Set<String> targetMerchants = userMerchantRepository.findByIdUserId(targetUserId).stream()
                .map(m -> m.getId().getMerchantId())
                .collect(Collectors.toSet());
        boolean overlap = targetMerchants.stream().anyMatch(merchants::contains);
        if (!overlap) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权管理该成员");
        }
        return target;
    }

    private MerchantUserDto toMerchantUserDto(UserInfo user, boolean self) {
        String roleKey = resolveMerchantRoleKey(user.getUserId());
        String roleName = switch (roleKey) {
            case "merchant" -> "商户管理员";
            case "merchant_store_manager" -> "店长";
            case "merchant_finance" -> "财务";
            case "merchant_replenisher" -> "补货员";
            case "merchant_staff" -> "店员";
            default -> roleKey;
        };
        return new MerchantUserDto(
                user.getUserId(),
                user.getPhoneNumber(),
                user.getName(),
                roleKey,
                roleName,
                user.getStatus() == null ? "ACTIVE" : user.getStatus(),
                self
        );
    }

    private List<MerchantDeviceDto> buildDeviceDtos(List<DeviceInfo> devices) {
        Set<String> replenishing = replenishmentTaskRepository.findByStatusInOrderByCreatedAtAsc(List.of("IN_PROGRESS"), 500).stream()
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
                d.salesLockedEnabled()
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
                d.salesLockedEnabled()
        );
    }

    private ReplenishmentTaskDto toReplenishmentTaskDto(ReplenishmentTask t) {
        return new ReplenishmentTaskDto(
                t.getTaskId(), t.getRouteId(), t.getDeviceId(), t.getAssigneeUserId(),
                t.getStatus(), t.getNotes(), t.getCompletedAt(),
                t.getCheckInAt(), t.getCheckInLat(), t.getCheckInLng(),
                resolveCheckInDistanceM(t),
                t.getRequestId(), t.getOutboundId(), t.getCreatedAt()
        );
    }

    private Double resolveCheckInDistanceM(ReplenishmentTask t) {
        if (t.getCheckInLat() == null || t.getCheckInLng() == null || t.getDeviceId() == null) {
            return null;
        }
        DeviceInfo device = deviceRepository.findById(t.getDeviceId()).orElse(null);
        if (device == null || device.getLatitude() == null || device.getLongitude() == null) {
            return null;
        }
        double r = 6371000;
        double dLat = Math.toRadians(t.getCheckInLat() - device.getLatitude());
        double dLon = Math.toRadians(t.getCheckInLng() - device.getLongitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(device.getLatitude())) * Math.cos(Math.toRadians(t.getCheckInLat()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private ReplenishmentTaskLineDto toReplenishmentLineDto(ReplenishmentTaskLine line) {
        return new ReplenishmentTaskLineDto(
                line.getLineId(), line.getLineType(), line.getSkuId(), line.getBatchNo(),
                line.getProductionDate(), line.getExpiryDate(), line.getQuantity(),
                line.getSlotId(), line.isApplied()
        );
    }

    private long resolveMerchantRoleId(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return MERCHANT_STAFF_ROLE_ID;
        }
        String key = roleKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "merchant", "merchant_admin" -> MERCHANT_ROLE_ID;
            case "merchant_finance" -> MERCHANT_FINANCE_ROLE_ID;
            case "merchant_store_manager" -> MERCHANT_STORE_MANAGER_ROLE_ID;
            case "merchant_replenisher" -> MERCHANT_REPLENISHER_ROLE_ID;
            case "merchant_staff" -> MERCHANT_STAFF_ROLE_ID;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的角色: " + roleKey);
        };
    }

    private String resolveMerchantRoleKey(Long userId) {
        return userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()))
                .flatMap(Optional::stream)
                .map(OpsRole::getRoleKey)
                .filter(MERCHANT_TEAM_ROLE_KEYS::contains)
                .map(key -> "merchant_admin".equals(key) ? "merchant" : key)
                .findFirst()
                .orElse("merchant_staff");
    }

    private static boolean isTempOutOfRange(DeviceInfo d) {
        if (d.getTargetTempC() == null || d.getCurrentTempC() == null) {
            return false;
        }
        return Math.abs(d.getCurrentTempC() - d.getTargetTempC()) > 3;
    }

    private MerchantDisputeSummaryDto toMerchantDisputeSummary(DisputeTicket ticket) {
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        String deviceId = session != null ? session.getDeviceId() : null;
        String orderId = session != null ? session.getOrderId() : null;
        Integer billedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .filter(o -> !"REFUNDED".equals(o.getStatus()))
                .map(CabinetOrder::getTotalAmountCents)
                .orElse(null);
        Instant now = Instant.now();
        boolean slaOverdue = "OPEN".equals(ticket.getStatus())
                && ticket.getSlaDueAt() != null
                && !ticket.getSlaDueAt().isAfter(now);
        Long slaHoursRemaining = null;
        if ("OPEN".equals(ticket.getStatus()) && ticket.getSlaDueAt() != null && !slaOverdue) {
            slaHoursRemaining = ChronoUnit.HOURS.between(now, ticket.getSlaDueAt());
        }
        return new MerchantDisputeSummaryDto(
                ticket.getTicketId(), ticket.getSessionId(), deviceId, ticket.getReason(),
                ticket.getStatus(), ticket.getCreatedAt(), ticket.getResolvedAt(),
                orderId, billedAmountCents,
                ticket.getSlaDueAt(), slaOverdue, slaHoursRemaining,
                ticket.getCategory()
        );
    }

    private RevenueSplitDto toSplitDto(OrderRevenueSplit s, String merchantName) {
        return new RevenueSplitDto(
                s.getSplitId(), s.getOrderId(), s.getMerchantId(), merchantName,
                s.getDeviceId(), s.getGrossCents(), s.getPlatformCents(),
                s.getMerchantCents(), s.getStatus(), s.getWechatOutOrderNo(),
                s.getWechatTransactionId(), s.getFailureReason(), s.getCreatedAt(),
                s.getSettlementBatchNo(), s.getSettleAfter(), s.getSettledAt()
        );
    }

    private ProfitSharingStatusDto buildProfitSharingStatus() {
        boolean enabled = profitSharingProperties.enabled();
        boolean apiReady = profitSharingService.isApiReady();
        boolean mock = profitSharingService.isMockMode();
        String note;
        if (!enabled) {
            note = "平台分账功能未启用，当前为记账模式";
        } else if (mock) {
            note = "平台分账联调 Mock 已启用";
        } else if (!weChatPayProperties.isConfigured()) {
            note = "微信支付未配置，分账将延迟到账";
        } else if (!apiReady) {
            note = "分账 API 未就绪，请联系平台运营";
        } else {
            note = "分账 API 已就绪，待分账款项将由平台定期提交";
        }
        return new ProfitSharingStatusDto(
                enabled, apiReady, profitSharingProperties.retryEnabled(),
                profitSharingProperties.retryBatchSize(),
                mock ? "MOCK" : (weChatPayProperties.isConfigured() ? "CONFIGURED" : "MISSING"),
                note
        );
    }

    private MerchantDailySettlementDto toDailySettlement(Object[] row) {
        return new MerchantDailySettlementDto(
                String.valueOf(at(row, 0)),
                toLong(at(row, 1)), toLong(at(row, 2)), toLong(at(row, 3)), toLong(at(row, 4)),
                toLong(at(row, 5)), toLong(at(row, 6)), toLong(at(row, 7))
        );
    }

    private MerchantSettlementBatchDto toBatchSettlement(Object[] row, Map<String, String> merchantNames) {
        String batchNo = at(row, 0) != null ? String.valueOf(at(row, 0)) : null;
        String merchantId = at(row, 1) != null ? String.valueOf(at(row, 1)) : null;
        LocalDate settleAfter = toLocalDate(at(row, 2));
        Instant settledAt = toInstant(at(row, 3));
        long orderCount = toLong(at(row, 4));
        long gross = toLong(at(row, 5));
        long platform = toLong(at(row, 6));
        long merchant = toLong(at(row, 7));
        long settled = toLong(at(row, 8));
        long pending = toLong(at(row, 9));
        long failed = toLong(at(row, 10));
        String status = failed > 0 ? "PARTIAL_FAILED" : (pending > 0 ? "PENDING" : "SETTLED");
        return new MerchantSettlementBatchDto(
                batchNo, merchantId, merchantNames.get(merchantId), settleAfter, settledAt,
                orderCount, gross, platform, merchant, settled, pending, failed, status
        );
    }

    private static Object at(Object[] row, int index) {
        return row != null && index >= 0 && index < row.length ? row[index] : null;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (value instanceof Instant i) {
            return LocalDate.ofInstant(i, ZoneId.systemDefault());
        }
        String raw = String.valueOf(value).trim();
        if (raw.length() >= 10 && raw.charAt(4) == '-' && raw.charAt(7) == '-') {
            return LocalDate.parse(raw.substring(0, 10));
        }
        return null;
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Number n) {
            long epoch = n.longValue();
            return Instant.ofEpochMilli(epoch < 100_000_000_000L ? epoch * 1000L : epoch);
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || raw.matches("^\\d{1,2}$")) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sessionDeviceId(String sessionId) {
        return sessionRepository.findById(sessionId).map(ShoppingSession::getDeviceId).orElse(null);
    }

    private static boolean inDeviceScope(Set<String> allowed, String deviceId) {
        if (deviceId == null) return false;
        return allowed == null || allowed.contains(deviceId);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }

    private static String normalizedStatus(String status) {
        return status != null ? status.toUpperCase() : null;
    }

    private static Instant parseDateStart(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant parseDateEnd(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return LocalDate.parse(date.trim()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant resolveSplitRangeStart(String fromDate) {
        Instant from = parseDateStart(fromDate);
        if (from != null) {
            return from;
        }
        return LocalDate.now(ZoneId.systemDefault()).minusYears(10)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant resolveSplitRangeEnd(String toDate) {
        Instant to = parseDateEnd(toDate);
        if (to != null) {
            return to;
        }
        return LocalDate.now(ZoneId.systemDefault()).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static int severityRank(String severity) {
        return switch (severity != null ? severity : "") {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static String formatDisputeReason(String reason) {
        if (reason == null || reason.isBlank()) return "识别结果需人工审核";
        return reason.trim();
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
}
