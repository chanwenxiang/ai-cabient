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
import org.springframework.data.domain.Sort;
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
    private static final int EXPORT_LIMIT = 5000;
    private static final long MERCHANT_ROLE_ID = 6L;
    private static final long MERCHANT_STAFF_ROLE_ID = 7L;

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
    private final SettlementService settlementService;
    private final AdminAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final DeviceTemperatureReadingMapper temperatureReadingRepository;
    private final DeviceServiceClient deviceServiceClient;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingProperties profitSharingProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final OperatorUserIdAllocator operatorUserIdAllocator;
    private final MerchantSelfServiceGate merchantSelfServiceGate;

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
                                 SettlementService settlementService,
                                 AdminAuditService auditService,
                                 PasswordEncoder passwordEncoder,
                                 DeviceTemperatureReadingMapper temperatureReadingRepository,
                                 DeviceServiceClient deviceServiceClient,
                                 WeChatProfitSharingService profitSharingService,
                                 ProfitSharingProperties profitSharingProperties,
                                 WeChatPayProperties weChatPayProperties,
                                 OperatorUserIdAllocator operatorUserIdAllocator,
                                 MerchantSelfServiceGate merchantSelfServiceGate) {
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
        this.settlementService = settlementService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.temperatureReadingRepository = temperatureReadingRepository;
        this.deviceServiceClient = deviceServiceClient;
        this.profitSharingService = profitSharingService;
        this.profitSharingProperties = profitSharingProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.operatorUserIdAllocator = operatorUserIdAllocator;
        this.merchantSelfServiceGate = merchantSelfServiceGate;
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
        List<String> permissions = permissionRepository.findPermCodesByUserId(userId).stream()
                .filter(p -> p.startsWith("merchant:"))
                .sorted()
                .toList();
        boolean canEditPricing = merchants.stream().anyMatch(MerchantDto::allowMerchantPricingEdit);
        return new MerchantMeDto(user.getUserId(), user.getPhoneNumber(), user.getName(), merchants, permissions, canEditPricing);
    }

    @Transactional(readOnly = true)
    public MerchantDashboardStatsDto getStats(Long userId) {
        merchantPortalGuard.requireAccess(userId);
        List<DeviceInfo> devices = merchantScopeService.allowedDevices(userId);
        Set<String> deviceIds = devices.stream().map(DeviceInfo::getDeviceId).collect(Collectors.toSet());
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

        long ordersToday = deviceIds.isEmpty() ? 0
                : orderRepository.countByDeviceIdInAndCreatedAtAfter(deviceIds, startOfDay);
        long revenueToday = deviceIds.isEmpty() ? 0
                : orderRepository.sumTotalAmountByDeviceIdInSince(deviceIds, startOfDay);

        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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

        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(userId);
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);

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
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(userId);
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
        if (deviceIds != null && deviceIds.isEmpty()) {
            return new MerchantWorkbenchDto(0, 0, 0, 0, 0, 0, List.of());
        }

        List<OpsActionItemDto> items = new ArrayList<>();

        long openDisputes = deviceIds == null ? disputeRepository.countByStatus("OPEN")
                : disputeRepository.countOpenByDeviceIds(deviceIds);
        disputeRepository.findTop10ByStatusOrderBySlaDueAtAscCreatedAtAsc("OPEN").stream()
                .filter(d -> inDeviceScope(deviceIds, sessionDeviceId(d.getSessionId())))
                .forEach(d -> items.add(new OpsActionItemDto(
                        "DISPUTE", "HIGH", "待审核争议",
                        formatDisputeReason(d.getReason()),
                        sessionDeviceId(d.getSessionId()), d.getSessionId(), d.getTicketId(),
                        null, null, d.getCreatedAt(), d.getSlaDueAt())));

        long offline = 0;
        for (DeviceInfo d : deviceRepository.findTop10ByOnlineStatusNotOrderByUpdatedAtAsc("ONLINE")) {
            if (!inDeviceScope(deviceIds, d.getDeviceId())) continue;
            offline++;
            items.add(new OpsActionItemDto(
                    "DEVICE_OFFLINE", "HIGH", "柜机离线",
                    d.getDeviceName() != null ? d.getDeviceName() : d.getDeviceId(),
                    d.getDeviceId(), null, null, null, null, d.getUpdatedAt(), null));
        }

        long lowStock = 0;
        for (DeviceSkuInventory inv : inventoryRepository.findLowStock()) {
            if (!inDeviceScope(deviceIds, inv.getId().getDeviceId())) continue;
            lowStock++;
            items.add(new OpsActionItemDto(
                    "LOW_STOCK", "MEDIUM", "库存偏低",
                    "SKU " + inv.getId().getSkuId() + " 当前 " + inv.getQuantity()
                            + " / 阈值 " + inv.getLowThreshold(),
                    inv.getId().getDeviceId(), null, null, inv.getId().getSkuId(),
                    null, inv.getUpdatedAt(), null));
        }

        long expiry = 0;
        for (PullOffTask task : pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN")) {
            if (!inDeviceScope(deviceIds, task.getDeviceId())) continue;
            expiry++;
            items.add(new OpsActionItemDto(
                    "EXPIRY", "MEDIUM", "临期/过期下架",
                    "SKU " + task.getSkuId() + " · " + task.getReason(),
                    task.getDeviceId(), null, null, task.getSkuId(),
                    task.getTaskId(), task.getCreatedAt(), null));
        }

        List<SlotDiscrepancyAlertDto> discrepancies = deviceSlotService.listDiscrepancyAlerts(userId, null);
        discrepancies.forEach(a -> items.add(new OpsActionItemDto(
                "SLOT_DISCREPANCY", "MEDIUM", "货道账实差异",
                a.slotCode() + " 账面 " + a.bookQty() + " 实测 " + a.physicalQty(),
                a.deviceId(), null, null, a.assignedSkuId(),
                null, a.lastPhysicalAt(), null)));

        replenishmentTaskRepository.findTop10ByStatusInOrderByCreatedAtAsc(List.of("PENDING", "IN_PROGRESS")).stream()
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
                items.stream().limit(20).toList()
        );
    }

    @Transactional(readOnly = true)
    public List<MerchantDeviceDto> listDevices(Long userId) {
        permissionService.requirePermission(userId, "merchant:devices:list");
        merchantPortalGuard.requireAccess(userId);
        return buildDeviceDtos(merchantScopeService.allowedDevices(userId));
    }

    @Transactional(readOnly = true)
    public DeviceDetailDto getDeviceDetail(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:detail");
        merchantPortalGuard.requireAccess(userId);
        return deviceSlotService.getDeviceDetail(userId, deviceId);
    }

    @Transactional(readOnly = true)
    public MerchantDeviceSettingsDto getDeviceSettings(Long userId, String deviceId) {
        permissionService.requirePermission(userId, "merchant:devices:detail");
        merchantPortalGuard.requireAccess(userId);
        merchantScopeService.requireDeviceAccess(userId, deviceId);
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        return toDeviceSettings(device);
    }

    @Transactional
    public MerchantDeviceSettingsDto updateDeviceSettings(Long userId, String deviceId,
                                                          UpdateMerchantDeviceSettingsRequest request) {
        permissionService.requirePermission(userId, "merchant:devices:edit");
        merchantPortalGuard.requireAccess(userId);
        merchantScopeService.requireDeviceAccess(userId, deviceId);
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
                "name=" + device.getDeviceName());
        return toDeviceSettings(device, tempCommandSent, tempCommandMessage);
    }

    @Transactional(readOnly = true)
    public List<DeviceTemperatureReadingDto> getTemperatureHistory(Long userId, String deviceId, int hours) {
        permissionService.requirePermission(userId, "merchant:temp:history");
        merchantPortalGuard.requireAccess(userId);
        merchantScopeService.requireDeviceAccess(userId, deviceId);
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
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
                .collect(Collectors.toMap(ShoppingSession::getDeviceId, s -> s, (a, b) -> a));

        return merchantScopeService.allowedDevices(userId).stream()
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
        permissionService.requirePermission(userId, "merchant:orders:list");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CabinetOrder> result = queryOrders(userId, deviceId, pageable);
        return new PageResult<>(
                result.getContent().stream().map(this::toMerchantOrderSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long userId, String orderId) {
        permissionService.requirePermission(userId, "merchant:orders:list");
        merchantPortalGuard.requireAccess(userId);
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ORDER_NOT_FOUND));
        merchantScopeService.requireDeviceAccess(userId, order.getDeviceId());
        return settlementService.getOrderBySession(order.getSessionId());
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantDisputeSummaryDto> listDisputes(Long userId, int page, int size,
                                                     String status, String deviceId) {
        permissionService.requirePermission(userId, "merchant:disputes:list");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(userId, deviceId);
        Page<DisputeTicket> result;
        if (deviceScope != null && deviceScope.isEmpty()) {
            result = Page.empty(pageable);
        } else if (deviceScope != null) {
            result = disputeRepository.searchByDeviceIds(
                    blankToNull(status), null, deviceScope, null, null, pageable);
        } else {
            result = disputeRepository.search(blankToNull(status), null, blankToNull(deviceId), null, null, pageable);
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
        Set<String> allowed = merchantScopeService.allowedDeviceIds(userId);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }

        List<DeviceSkuInventory> rows = lowStockOnly
                ? inventoryRepository.findLowStock()
                : (deviceId != null && !deviceId.isBlank()
                ? inventoryRepository.findByIdDeviceId(deviceId.trim())
                : inventoryRepository.findAll());

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
        Set<String> allowed = merchantScopeService.allowedDeviceIds(userId);
        return pullOffTaskRepository.findByStatusOrderByCreatedAtDesc("OPEN").stream()
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
        return deviceSlotService.listDiscrepancyAlerts(userId, deviceId);
    }

    @Transactional
    public List<MerchantDto> updateProfile(Long userId, UpdateMerchantProfileRequest request) {
        permissionService.requirePermission(userId, "merchant:profile:edit");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
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
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
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
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
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
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        Pageable pageable = PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CabinetOrder> page = queryOrders(userId, deviceId, pageable);
        StringBuilder sb = new StringBuilder("orderId,sessionId,deviceId,totalAmountCents,status,lineCount,createdAt\n");
        for (CabinetOrder o : page.getContent()) {
            sb.append(csv(o.getOrderId())).append(',')
                    .append(csv(o.getSessionId())).append(',')
                    .append(csv(o.getDeviceId())).append(',')
                    .append(o.getTotalAmountCents()).append(',')
                    .append(csv(o.getStatus())).append(',')
                    .append(o.getLines().size()).append(',')
                    .append(csv(String.valueOf(o.getCreatedAt()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] exportSplitsCsv(Long userId, String status, String fromDate, String toDate) {
        permissionService.requirePermission(userId, "merchant:reports:export");
        merchantPortalGuard.requireAccess(userId);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
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
        Set<String> allowed = merchantScopeService.allowedDeviceIds(userId);
        if (allowed != null && allowed.isEmpty()) {
            return List.of();
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantScopeService.requireDeviceAccess(userId, deviceId.trim());
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
        merchantScopeService.requireDeviceAccess(userId, task.getDeviceId());
        return replenishmentTaskLineRepository.findByTaskIdOrderByLineIdAsc(taskId).stream()
                .map(this::toReplenishmentLineDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantUserDto> listTeamUsers(Long userId) {
        permissionService.requirePermission(userId, "merchant:users:list");
        merchantPortalGuard.requireAccess(userId);
        Set<String> merchants = merchantScopeService.allowedMerchantIds(userId);
        Set<Long> userIds = userMerchantRepository.findByMerchantIdIn(merchants).stream()
                .map(m -> m.getId().getUserId())
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return List.of();
        }
        return userInfoRepository.findByUserIdIn(new ArrayList<>(userIds)).stream()
                .sorted(Comparator.comparing(UserInfo::getUserId))
                .map(u -> new MerchantUserDto(
                        u.getUserId(), u.getPhoneNumber(), u.getName(),
                        resolveMerchantRoleKey(u.getUserId()), u.getUserId().equals(userId)))
                .toList();
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
        Set<String> merchants = merchantScopeService.allowedMerchantIds(userId);
        long newUserId = operatorUserIdAllocator.nextId();

        UserInfo user = new UserInfo();
        user.setUserId(newUserId);
        user.setPhoneNumber(phone);
        user.setName(request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim() : "商户成员");
        user.setVerified(true);
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
        return new MerchantUserDto(newUserId, phone, user.getName(), resolveRoleKey(roleId), false);
    }

    private List<MerchantDeviceDto> buildDeviceDtos(List<DeviceInfo> devices) {
        Set<String> replenishing = replenishmentTaskRepository.findByStatusIn(List.of("IN_PROGRESS")).stream()
                .map(ReplenishmentTask::getDeviceId)
                .collect(Collectors.toSet());
        Map<String, ShoppingSession> activeByDevice = sessionRepository.findAll().stream()
                .filter(s -> ACTIVE_STATES.contains(s.getState()))
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

    private Page<CabinetOrder> queryOrders(Long userId, String deviceId, Pageable pageable) {
        Collection<String> deviceScope = merchantScopeService.intersectDeviceFilter(userId, deviceId);
        if (deviceScope != null && deviceScope.isEmpty()) {
            return Page.empty(pageable);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            merchantScopeService.requireDeviceAccess(userId, deviceId.trim());
            return orderRepository.findByDeviceIdOrderByCreatedAtDesc(deviceId.trim(), pageable);
        }
        if (deviceScope != null) {
            return orderRepository.findByDeviceIdInOrderByCreatedAtDesc(deviceScope, pageable);
        }
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private MerchantDto toMerchantDto(Merchant m, long deviceCount) {
        return new MerchantDto(
                m.getMerchantId(), m.getMerchantName(), m.getContactPhone(),
                m.getAlertContactName(), m.getAlertContactPhone(),
                m.getPlatformRateBps(), m.getWechatReceiverId(), m.getStatus(),
                m.getRemark(), deviceCount,
                m.isAllowMerchantPlanogramEdit(), m.isAllowMerchantPricingEdit(),
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
                d.getUpdatedAt(), replenishmentInProgress
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
                isTempOutOfRange(d), d.getOpsRemark(), tempCommandSent, tempCommandMessage
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
        if (roleKey != null && "merchant_staff".equalsIgnoreCase(roleKey.trim())) {
            return MERCHANT_STAFF_ROLE_ID;
        }
        return MERCHANT_ROLE_ID;
    }

    private String resolveMerchantRoleKey(Long userId) {
        return userRoleRepository.findByIdUserId(userId).stream()
                .map(ur -> roleRepository.findById(ur.getId().getRoleId()))
                .flatMap(Optional::stream)
                .map(OpsRole::getRoleKey)
                .filter(key -> "merchant".equals(key) || "merchant_staff".equals(key))
                .findFirst()
                .orElse("merchant");
    }

    private String resolveRoleKey(long roleId) {
        return roleRepository.findById(roleId).map(OpsRole::getRoleKey).orElse("merchant");
    }

    private static boolean isTempOutOfRange(DeviceInfo d) {
        if (d.getTargetTempC() == null || d.getCurrentTempC() == null) {
            return false;
        }
        return Math.abs(d.getCurrentTempC() - d.getTargetTempC()) > 3;
    }

    private MerchantOrderSummaryDto toMerchantOrderSummary(CabinetOrder o) {
        return new MerchantOrderSummaryDto(
                o.getOrderId(), o.getSessionId(), o.getDeviceId(),
                o.getTotalAmountCents(), o.getStatus(), o.getLines().size(), o.getCreatedAt()
        );
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
                String.valueOf(row[0]),
                toLong(row[1]), toLong(row[2]), toLong(row[3]), toLong(row[4]),
                toLong(row[5]), toLong(row[6]), toLong(row[7])
        );
    }

    private MerchantSettlementBatchDto toBatchSettlement(Object[] row, Map<String, String> merchantNames) {
        String batchNo = row[0] != null ? String.valueOf(row[0]) : null;
        String merchantId = row[1] != null ? String.valueOf(row[1]) : null;
        LocalDate settleAfter = toLocalDate(row[2]);
        Instant settledAt = toInstant(row[3]);
        long orderCount = toLong(row[4]);
        long gross = toLong(row[5]);
        long platform = toLong(row[6]);
        long merchant = toLong(row[7]);
        long settled = toLong(row[8]);
        long pending = toLong(row[9]);
        long failed = toLong(row[10]);
        String status = failed > 0 ? "PARTIAL_FAILED" : (pending > 0 ? "PENDING" : "SETTLED");
        return new MerchantSettlementBatchDto(
                batchNo, merchantId, merchantNames.get(merchantId), settleAfter, settledAt,
                orderCount, gross, platform, merchant, settled, pending, failed, status
        );
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
        return LocalDate.parse(String.valueOf(value));
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
        return Instant.parse(String.valueOf(value));
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

