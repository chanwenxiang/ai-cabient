package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.DeviceNameSupport;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class MerchantPortalService {


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
    private final ReplenishmentRouteMapper replenishmentRouteRepository;
    private final DisputeTicketMapper disputeRepository;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final PullOffTaskMapper pullOffTaskRepository;
    private final DeviceSlotService deviceSlotService;
    private final InventoryLotService inventoryLotService;
    private final AdminAuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final DeviceTemperatureReadingMapper temperatureReadingRepository;
    private final DeviceServiceClient deviceServiceClient;
    private final OperatorUserIdAllocator operatorUserIdAllocator;
    private final MerchantSelfServiceGate merchantSelfServiceGate;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final DistributedLockService distributedLockService;
    private final DisputeService disputeService;
    private final MerchantWorkbenchQueryService workbenchQueryService;
    private final MerchantDevicePortalService devicePortalService;
    private final MerchantInventoryPortalService inventoryPortalService;
    private final MerchantTeamAdminService teamAdminService;
    private final SystemConfigService systemConfigService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final MerchantPortalService self;

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
                                 ReplenishmentRouteMapper replenishmentRouteRepository,
                                 DisputeTicketMapper disputeRepository,
                                 DeviceSkuInventoryMapper inventoryRepository,
                                 PullOffTaskMapper pullOffTaskRepository,
                                 DeviceSlotService deviceSlotService,
                                 InventoryLotService inventoryLotService,
                                 AdminAuditService auditService,
                                 PasswordEncoder passwordEncoder,
                                 DeviceTemperatureReadingMapper temperatureReadingRepository,
                                 DeviceServiceClient deviceServiceClient,
                                 OperatorUserIdAllocator operatorUserIdAllocator,
                                 MerchantSelfServiceGate merchantSelfServiceGate,
                                 MerchantFeaturePackService merchantFeaturePackService,
                                 DistributedLockService distributedLockService,
                                 @Lazy DisputeService disputeService,
                                 MerchantWorkbenchQueryService workbenchQueryService,
                                 MerchantDevicePortalService devicePortalService,
                                 MerchantInventoryPortalService inventoryPortalService,
                                 MerchantTeamAdminService teamAdminService,
                                 SystemConfigService systemConfigService,
                                 @Lazy MerchantPortalService self) {
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
        this.replenishmentRouteRepository = replenishmentRouteRepository;
        this.disputeRepository = disputeRepository;
        this.inventoryRepository = inventoryRepository;
        this.pullOffTaskRepository = pullOffTaskRepository;
        this.deviceSlotService = deviceSlotService;
        this.inventoryLotService = inventoryLotService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.temperatureReadingRepository = temperatureReadingRepository;
        this.deviceServiceClient = deviceServiceClient;
        this.operatorUserIdAllocator = operatorUserIdAllocator;
        this.merchantSelfServiceGate = merchantSelfServiceGate;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.distributedLockService = distributedLockService;
        this.disputeService = disputeService;
        this.workbenchQueryService = workbenchQueryService;
        this.devicePortalService = devicePortalService;
        this.inventoryPortalService = inventoryPortalService;
        this.teamAdminService = teamAdminService;
        this.systemConfigService = systemConfigService;
        this.self = self;
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
        boolean requireEvidence = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_COMPLETE_REQUIRE_EVIDENCE, true);
        boolean requireDoor = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_COMPLETE_REQUIRE_DOOR, true);
        boolean requireCheckInLocation = systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION, true);
        int checkInMaxDistanceM = systemConfigService.getInt(
                SystemConfigService.REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M, 500);
        return new MerchantMeDto(
                user.getUserId(), user.getPhoneNumber(), user.getName(),
                merchants, permissions, canEditPricing, enabledPacks,
                requireEvidence, requireDoor, requireCheckInLocation, checkInMaxDistanceM);
    }

    @Transactional(readOnly = true)
    public MerchantDashboardStatsDto getStats(Long userId) {
        return workbenchQueryService.getStats(userId);
    }

    @Transactional(readOnly = true)
    public MerchantTrendDto getTrend(Long userId, int days) {
        return workbenchQueryService.getTrend(userId, days);
    }

    @Transactional(readOnly = true)
    public MerchantWorkbenchDto getWorkbench(Long userId) {
        return workbenchQueryService.getWorkbench(userId);
    }

    @Transactional(readOnly = true)
    public List<MerchantDeviceDto> listDevices(Long userId) {
        return devicePortalService.listDevices(userId);
    }

    @Transactional(readOnly = true)
    public DeviceDetailDto getDeviceDetail(Long userId, String deviceId) {
        return devicePortalService.getDeviceDetail(userId, deviceId);
    }

    @Transactional(readOnly = true)
    public MerchantDeviceSettingsDto getDeviceSettings(Long userId, String deviceId) {
        return devicePortalService.getDeviceSettings(userId, deviceId);
    }

    @Transactional
    public MerchantDeviceSettingsDto updateDeviceSettings(Long userId, String deviceId,
                                                          UpdateMerchantDeviceSettingsRequest request) {
        return devicePortalService.updateDeviceSettings(userId, deviceId, request);
    }

    @Transactional(readOnly = true)
    public List<DeviceTemperatureReadingDto> getTemperatureHistory(Long userId, String deviceId, int hours) {
        return devicePortalService.getTemperatureHistory(userId, deviceId, hours);
    }

    @Transactional(readOnly = true)
    public List<MerchantDeviceReportDto> deviceReports(Long userId) {
        return devicePortalService.deviceReports(userId);
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantOrderSummaryDto> listOrders(Long userId, int page, int size, String deviceId) {
        return merchantFinanceService.listOrders(userId, new MerchantFinanceService.MerchantOrderListQuery(
                page, size, deviceId, null, null, null, null));
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
        return inventoryPortalService.listInventory(userId, deviceId, lowStockOnly);
    }

    @Transactional(readOnly = true)
    public List<PullOffTaskDto> listExpiryAlerts(Long userId) {
        return inventoryPortalService.listExpiryAlerts(userId);
    }

    @Transactional(readOnly = true)
    public List<SlotDiscrepancyAlertDto> listSlotDiscrepancies(Long userId, String deviceId) {
        return inventoryPortalService.listSlotDiscrepancies(userId, deviceId);
    }

    @Transactional
    public List<MerchantDto> updateProfile(Long userId, UpdateMerchantProfileRequest request) {
        permissionService.requirePermission(userId, "merchant:profile:edit");
        merchantPortalGuard.requireAccess(userId);
        return runWithMerchantProfileLock(userId, () -> doUpdateProfile(userId, request));
    }

    private List<MerchantDto> doUpdateProfile(Long userId, UpdateMerchantProfileRequest request) {
        Set<String> allowed = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.TEAM);
        if (allowed == null || allowed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
        List<Merchant> merchants = merchantRepository.findAll().stream()
                .filter(m -> allowed.contains(m.getMerchantId()))
                .toList();
        for (Merchant m : merchants) {
            Merchant locked = merchantRepository.findByIdForUpdate(m.getMerchantId()).orElse(m);
            if (request.contactPhone() != null) {
                locked.setContactPhone(blankToNull(request.contactPhone()));
            }
            if (request.alertContactName() != null) {
                locked.setAlertContactName(blankToNull(request.alertContactName()));
            }
            if (request.alertContactPhone() != null) {
                locked.setAlertContactPhone(blankToNull(request.alertContactPhone()));
            }
            merchantRepository.save(locked);
        }
        auditService.appendLog(userId, "MERCHANT_PROFILE_UPDATE", "MERCHANT",
                String.join(",", allowed), "profile updated");
        Map<String, Long> deviceCounts = deviceRepository.findByMerchantIdIn(allowed).stream()
                .collect(Collectors.groupingBy(DeviceInfo::getMerchantId, Collectors.counting()));
        return merchants.stream()
                .map(m -> toMerchantDto(m, deviceCounts.getOrDefault(m.getMerchantId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public MerchantSettlementOverviewDto getSettlementOverview(Long userId) {
        return merchantFinanceService.getSettlementOverview(userId);
    }

    @Transactional(readOnly = true)
    public List<MerchantDailySettlementDto> listDailySettlements(Long userId, String fromDate, String toDate) {
        return merchantFinanceService.listDailySettlements(userId, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<MerchantSettlementBatchDto> listSettlementBatches(Long userId, String fromDate, String toDate) {
        return merchantFinanceService.listSettlementBatches(userId, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<RevenueSplitDto> getSettlementBatchDetail(Long userId, String batchNo) {
        return merchantFinanceService.getSettlementBatchDetail(userId, batchNo);
    }

    @Transactional(readOnly = true)
    public byte[] exportSettlementsCsv(Long userId, String fromDate, String toDate) {
        return merchantFinanceService.exportSettlementsCsv(userId, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public PageResult<RevenueSplitDto> listSplits(Long userId, int page, int size,
                                                 String status, String fromDate, String toDate) {
        return merchantFinanceService.listSplits(userId, page, size, status, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long userId, String deviceId) {
        return merchantFinanceService.exportOrdersCsv(userId, deviceId);
    }

    @Transactional(readOnly = true)
    public byte[] exportSplitsCsv(Long userId, String status, String fromDate, String toDate) {
        return merchantFinanceService.exportSplitsCsv(userId, status, fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public byte[] exportDeviceReportsCsv(Long userId) {
        return devicePortalService.exportDeviceReportsCsv(userId);
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskDto> listReplenishmentTasks(Long userId, String status, String deviceId) {
        return inventoryPortalService.listReplenishmentTasks(userId, status, deviceId);
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentTaskLineDto> getReplenishmentTaskLines(Long userId, Long taskId) {
        return inventoryPortalService.getReplenishmentTaskLines(userId, taskId);
    }

    @Transactional(readOnly = true)
    public List<MerchantUserDto> listTeamUsers(Long userId) {
        return teamAdminService.listTeamUsers(userId);
    }

    @Transactional(readOnly = true)
    public List<MerchantTeamRoleDto> listTeamRoles(Long userId) {
        return teamAdminService.listTeamRoles(userId);
    }

    @Transactional
    public MerchantUserDto updateTeamUser(Long operatorId, Long targetUserId, UpdateMerchantUserRequest request) {
        return teamAdminService.updateTeamUser(operatorId, targetUserId, request);
    }

    @Transactional
    public MerchantUserDto disableTeamUser(Long operatorId, Long targetUserId) {
        return teamAdminService.disableTeamUser(operatorId, targetUserId);
    }

    @Transactional
    public MerchantUserDto enableTeamUser(Long operatorId, Long targetUserId) {
        return teamAdminService.enableTeamUser(operatorId, targetUserId);
    }

    @Transactional
    public MerchantUserDto resetTeamUserPassword(Long operatorId, Long targetUserId,
                                                 ResetMerchantUserPasswordRequest request) {
        return teamAdminService.resetTeamUserPassword(operatorId, targetUserId, request);
    }

    @Transactional
    public MerchantUserDto createTeamUser(Long userId, CreateMerchantUserRequest request) {
        return teamAdminService.createTeamUser(userId, request);
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

    private MerchantDisputeSummaryDto toMerchantDisputeSummary(DisputeTicket ticket) {
        ShoppingSession session = sessionRepository.findById(ticket.getSessionId()).orElse(null);
        String deviceId = session != null ? session.getDeviceId() : null;
        String orderId = session != null ? session.getOrderId() : null;
        Integer billedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .map(o -> {
                    int original = Math.max(0, o.getOriginalAmountCents());
                    if (original > 0) {
                        return original;
                    }
                    int total = Math.max(0, o.getTotalAmountCents());
                    int refunded = Math.max(0, o.getRefundedCents());
                    if (total <= 0 && refunded > 0) {
                        return refunded;
                    }
                    return total > 0 ? total : null;
                })
                .orElse(null);
        Integer refundedAmountCents = orderRepository.findBySessionId(ticket.getSessionId())
                .map(o -> {
                    int r = Math.max(0, o.getRefundedCents());
                    if (r > 0) {
                        return r;
                    }
                    if ("REFUNDED".equals(o.getStatus())) {
                        int original = Math.max(0, o.getOriginalAmountCents());
                        int total = Math.max(0, o.getTotalAmountCents());
                        int amount = original > 0 ? original : total;
                        return amount > 0 ? amount : null;
                    }
                    return null;
                })
                .orElse(null);
        Integer claimedAmountCents = disputeService.resolveClaimedAmountCents(ticket);
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
                ticket.getCategory(),
                refundedAmountCents,
                session != null ? session.getDeviceName() : null,
                claimedAmountCents,
                session != null && session.getVideoUri() != null && !session.getVideoUri().isBlank()
        );
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
        return devicePortalService.listDeviceSlots(userId, deviceId);
    }

    @Transactional
    public List<DeviceSlotDto> upsertDeviceSlots(Long userId, String deviceId,
                                                 List<UpsertDeviceSlotRequest> body) {
        return devicePortalService.upsertDeviceSlots(userId, deviceId, body);
    }

    static String merchantProfileLockKey(long userId) {
        return "merchant:portal:profile:" + userId;
    }

    static String merchantTeamPhoneLockKey(String phone) {
        return MerchantTeamAdminService.merchantTeamPhoneLockKey(phone);
    }

    private <T> T runWithMerchantProfileLock(long userId, Supplier<T> action) {
        return runWithLock(merchantProfileLockKey(userId), "商户资料处理中，请稍后重试", action);
    }

    private <T> T runWithLock(String lockKey, String busyMessage, Supplier<T> action) {
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, busyMessage);
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
}
