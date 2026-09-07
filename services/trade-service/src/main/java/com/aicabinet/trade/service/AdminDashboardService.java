package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.*;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.Member;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.AliyunCategoryMapping;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@SuppressWarnings("java:S6539")
public class AdminDashboardService {
    /** 工作台「待支付」与订单页 overdue=1 对齐：超过该分钟仍 PENDING 计入。 */
    public static final int UNPAID_OPS_OVERDUE_MINUTES = OpsSessionOrderQueryService.UNPAID_OPS_OVERDUE_MINUTES;

    private final DeviceInfoMapper deviceRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final CabinetOrderMapper orderRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DisputeTicketMapper disputeRepository;
    private final SettlementService settlementService;
    private final UserInfoMapper userInfoRepository;
    private final UserAccountMapper userAccountRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final AdminAuditService auditService;
    private final AdminAuditLogMapper auditLogRepository;
    private final PermissionService permissionService;
    private final PaymentService paymentService;
    private final RechargeOrderMapper rechargeOrderRepository;
    private final SlaMetricsService slaMetricsService;
    private final MinioVideoService minioVideoService;
    private final MerchantMapper merchantRepository;
    private final MerchantScopeService merchantScopeService;
    private final DeviceSkuInventoryMapper inventoryRepository;
    private final OrderRevenueSplitMapper splitRepository;
    private final DisputeSlaService disputeSlaService;
    private final InventoryLotService inventoryLotService;
    private final DeviceSlotService deviceSlotService;
    private final ReplenishmentTaskMapper replenishmentTaskRepository;
    private final PaymentReconciliationMapper reconciliationRepository;
    private final WarehouseInTransitMapper inTransitRepository;
    private final BalanceLedgerService balanceLedgerService;
    private final RefundPolicyService refundPolicyService;
    private final OpsExceptionMapper exceptionRepository;
    private final FileAttachmentService fileAttachmentService;
    private final MemberMapper memberRepository;
    private final UserBlacklistMapper blacklistRepository;
    private final DistributedLockService distributedLockService;
    private final AliyunCategoryMappingMapper aliyunCategoryMappingRepository;
    private final DeviceIdService deviceIdService;
    private final DeviceIdRenameService deviceIdRenameService;
    private final OpsAnalyticsQueryService analyticsQueryService;
    private final OpsAuditQueryService auditQueryService;
    private final OpsDeviceAdminService deviceAdminService;
    private final OpsCatalogAdminService catalogAdminService;
    private final OpsSessionOrderQueryService sessionOrderQueryService;
    private final OpsWorkbenchQueryService workbenchQueryService;
    private final OpsMemberFinanceAdminService memberFinanceAdminService;

    public AdminDashboardService(DeviceInfoMapper deviceRepository,
                                 ShoppingSessionMapper sessionRepository,
                                 CabinetOrderMapper orderRepository,
                                 CabinetOrderLineMapper orderLineRepository,
                                 DisputeTicketMapper disputeRepository,
                                 SettlementService settlementService,
                                 UserInfoMapper userInfoRepository,
                                 UserAccountMapper userAccountRepository,
                                 SkuCatalogMapper skuCatalogRepository,
                                 AdminAuditService auditService,
                                 AdminAuditLogMapper auditLogRepository,
                                 PermissionService permissionService,
                                 PaymentService paymentService,
                                 RechargeOrderMapper rechargeOrderRepository,
                                 SlaMetricsService slaMetricsService,
                                 MinioVideoService minioVideoService,
                                 MerchantMapper merchantRepository,
                                 MerchantScopeService merchantScopeService,
                                 DeviceSkuInventoryMapper inventoryRepository,
                                 OrderRevenueSplitMapper splitRepository,
                                 DisputeSlaService disputeSlaService,
                                 InventoryLotService inventoryLotService,
                                 DeviceSlotService deviceSlotService,
                                 ReplenishmentTaskMapper replenishmentTaskRepository,
                                 PaymentReconciliationMapper reconciliationRepository,
                                 WarehouseInTransitMapper inTransitRepository,
                                 BalanceLedgerService balanceLedgerService,
                                 RefundPolicyService refundPolicyService,
                                 OpsExceptionMapper exceptionRepository,
                                 FileAttachmentService fileAttachmentService,
                                 MemberMapper memberRepository,
                                 UserBlacklistMapper blacklistRepository,
                                 DistributedLockService distributedLockService,
                                 AliyunCategoryMappingMapper aliyunCategoryMappingRepository,
                                 DeviceIdService deviceIdService,
                                 DeviceIdRenameService deviceIdRenameService,
                                 OpsAnalyticsQueryService analyticsQueryService,
                                 OpsAuditQueryService auditQueryService,
                                 OpsDeviceAdminService deviceAdminService,
                                 OpsCatalogAdminService catalogAdminService,
                                 OpsSessionOrderQueryService sessionOrderQueryService,
                                 OpsWorkbenchQueryService workbenchQueryService,
                                 OpsMemberFinanceAdminService memberFinanceAdminService) {
        this.deviceRepository = deviceRepository;
        this.sessionRepository = sessionRepository;
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.disputeRepository = disputeRepository;
        this.settlementService = settlementService;
        this.userInfoRepository = userInfoRepository;
        this.userAccountRepository = userAccountRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.permissionService = permissionService;
        this.paymentService = paymentService;
        this.rechargeOrderRepository = rechargeOrderRepository;
        this.slaMetricsService = slaMetricsService;
        this.minioVideoService = minioVideoService;
        this.merchantRepository = merchantRepository;
        this.merchantScopeService = merchantScopeService;
        this.inventoryRepository = inventoryRepository;
        this.splitRepository = splitRepository;
        this.disputeSlaService = disputeSlaService;
        this.inventoryLotService = inventoryLotService;
        this.deviceSlotService = deviceSlotService;
        this.replenishmentTaskRepository = replenishmentTaskRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.inTransitRepository = inTransitRepository;
        this.balanceLedgerService = balanceLedgerService;
        this.refundPolicyService = refundPolicyService;
        this.exceptionRepository = exceptionRepository;
        this.fileAttachmentService = fileAttachmentService;
        this.memberRepository = memberRepository;
        this.blacklistRepository = blacklistRepository;
        this.distributedLockService = distributedLockService;
        this.aliyunCategoryMappingRepository = aliyunCategoryMappingRepository;
        this.deviceIdService = deviceIdService;
        this.deviceIdRenameService = deviceIdRenameService;
        this.analyticsQueryService = analyticsQueryService;
        this.auditQueryService = auditQueryService;
        this.deviceAdminService = deviceAdminService;
        this.catalogAdminService = catalogAdminService;
        this.sessionOrderQueryService = sessionOrderQueryService;
        this.workbenchQueryService = workbenchQueryService;
        this.memberFinanceAdminService = memberFinanceAdminService;
    }

    @Transactional(readOnly = true)
    public List<DeviceRefDto> listDeviceRefs(Long operatorId) {
        return deviceAdminService.listDeviceRefs(operatorId);
    }

    @Transactional(readOnly = true)
    public OpsDashboardBundleDto dashboardBundle(Long operatorId) {
        return workbenchQueryService.dashboardBundle(operatorId);
    }

    public AdminStatsDto stats(Long operatorId) {
        return workbenchQueryService.stats(operatorId);
    }

    @Transactional(readOnly = true)
    public OpsWorkbenchDto workbench(Long operatorId) {
        return workbenchQueryService.workbench(operatorId);
    }

    /** 在途超时告警聚合（表征测试 / 门面兼容入口）。 */
    public static List<OpsActionItemDto> aggregateInTransitOverdueActionItems(
            List<WarehouseInTransit> overdueLines) {
        return OpsWorkbenchQueryService.aggregateInTransitOverdueActionItems(overdueLines);
    }

    public List<AdminDeviceDto> listDevices(Long operatorId) {
        return deviceAdminService.listDevices(operatorId);
    }

    @Transactional(readOnly = true)
    public AdminDeviceDto getDevice(Long operatorId, String deviceId) {
        return deviceAdminService.getDevice(operatorId, deviceId);
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                         String q, String online) {
        return deviceAdminService.listDevicesPaged(operatorId, page, size, q, online);
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, int page, int size,
                                                         String q, String online, Boolean salesLocked) {
        return deviceAdminService.listDevicesPaged(operatorId, page, size, q, online, salesLocked);
    }

    public PageResult<AdminDeviceDto> listDevicesPaged(Long operatorId, OpsDeviceAdminService.DeviceListQuery query) {
        return deviceAdminService.listDevicesPaged(operatorId, query);
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, int page, int size,
                                                      String deviceId, SessionState state) {
        return sessionOrderQueryService.listSessions(operatorId, page, size, deviceId, state);
    }

    public PageResult<AdminSessionDto> listSessions(Long operatorId, OpsSessionOrderQueryService.SessionListQuery query) {
        return sessionOrderQueryService.listSessions(operatorId, query);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, int page, int size, String deviceId) {
        return sessionOrderQueryService.listOrders(operatorId, page, size, deviceId);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status) {
        return sessionOrderQueryService.listOrders(operatorId, page, size, deviceId, status);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(
            Long operatorId, int page, int size, String deviceId, String status, boolean overdueOnly) {
        return sessionOrderQueryService.listOrders(operatorId, page, size, deviceId, status, overdueOnly);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminOrderSummaryDto> listOrders(Long operatorId, OpsSessionOrderQueryService.OrderListQuery query) {
        return sessionOrderQueryService.listOrders(operatorId, query);
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long operatorId, String orderId) {
        return sessionOrderQueryService.getOrder(operatorId, orderId);
    }

    @Transactional
    public AdminSessionDto cancelSession(Long operatorId, String sessionId) {
        return sessionOrderQueryService.cancelSession(operatorId, sessionId);
    }

    @Transactional(readOnly = true)
    public void streamSessionVideo(Long operatorId, String sessionId,
                                   jakarta.servlet.http.HttpServletRequest request,
                                   HttpServletResponse response) {
        sessionOrderQueryService.streamSessionVideo(operatorId, sessionId, request, response);
    }

    @Transactional(readOnly = true)
    public List<com.aicabinet.common.dto.DeviceMapPointDto> listDeviceMapPoints(
            Long operatorId, String lifecycleStatus, String routeCode, String online) {
        return deviceAdminService.listDeviceMapPoints(operatorId, lifecycleStatus, routeCode, online);
    }

    public PageResult<AdminDeviceReportDto> deviceReports(
            Long operatorId,
            int page,
            int size,
            String keyword,
            String online,
            String deviceId) {
        return deviceAdminService.deviceReports(operatorId, page, size, keyword, online, deviceId);
    }

    /** @deprecated 兼容旧调用：返回全量列表 */
    @Deprecated(since = "2026-08", forRemoval = false)
    @SuppressWarnings("java:S1133")
    public List<AdminDeviceReportDto> deviceReports(Long operatorId) {
        return deviceAdminService.deviceReports(operatorId);
    }

    public PageResult<AdminAuditLogDto> listAuditLogs(Long operatorId, int page, int size, boolean logIdAsc) {
        return auditQueryService.listAuditLogs(operatorId, page, size, logIdAsc);
    }

    public PageResult<AdminAuditLogDto> listAuditLogs(
            Long operatorId,
            int page,
            int size,
            boolean logIdAsc,
            String action,
            String targetType,
            boolean mineOnly) {
        return auditQueryService.listAuditLogs(operatorId, page, size, logIdAsc, action, targetType, mineOnly);
    }

    public List<AdminAuditLogDto> listRecentAuditLogs(Long operatorId, int size, boolean mineOnly) {
        return auditQueryService.listRecentAuditLogs(operatorId, size, mineOnly);
    }

    public PageResult<AdminUserDto> listUsers(Long operatorId, int page, int size, Long userId,
                                              String phone, String name, String role, Boolean verified) {
        return memberFinanceAdminService.listUsers(operatorId, page, size, userId, phone, name, role, verified);
    }

    public List<SkuCatalogDto> listSkus(Long operatorId) {
        return catalogAdminService.listSkus(operatorId);
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkus(Long operatorId, String q, String status, String category) {
        return catalogAdminService.listSkus(operatorId, q, status, category);
    }

    @Transactional(readOnly = true)
    public PageResult<SkuCatalogDto> listSkusPage(
            Long operatorId, String q, String status, String category, int page, int size) {
        return catalogAdminService.listSkusPage(operatorId, q, status, category, page, size);
    }

    @Transactional
    public SkuCatalogDto createSku(Long operatorId, UpsertSkuRequest request) {
        return catalogAdminService.createSku(operatorId, request);
    }

    @Transactional
    public SkuCatalogDto updateSku(Long operatorId, String skuId, UpsertSkuRequest request) {
        return catalogAdminService.updateSku(operatorId, skuId, request);
    }

    @Transactional
    public AdminDeviceDto createDevice(Long operatorId, UpsertDeviceRequest request) {
        return deviceAdminService.createDevice(operatorId, request);
    }

    @Transactional
    public AdminDeviceDto resetHardwareBinding(Long operatorId, String deviceId) {
        return deviceAdminService.resetHardwareBinding(operatorId, deviceId);
    }

    @Transactional
    public AdminDeviceDto regenerateDeviceId(Long operatorId, String oldDeviceId) {
        return deviceAdminService.regenerateDeviceId(operatorId, oldDeviceId);
    }

    @Transactional
    public AdminDeviceDto updateDevice(Long operatorId, String deviceId, UpdateDeviceRequest request) {
        return deviceAdminService.updateDevice(operatorId, deviceId, request);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId) {
        return sessionOrderQueryService.exportOrdersCsv(operatorId, deviceId);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, String deviceId, String status, String mode) {
        return sessionOrderQueryService.exportOrdersCsv(operatorId, deviceId, status, mode);
    }

    @Transactional(readOnly = true)
    public byte[] exportOrdersCsv(Long operatorId, OpsSessionOrderQueryService.OrderExportQuery query) {
        return sessionOrderQueryService.exportOrdersCsv(operatorId, query);
    }

    public byte[] exportSessionsCsv(Long operatorId, String deviceId, SessionState state) {
        return sessionOrderQueryService.exportSessionsCsv(operatorId, deviceId, state);
    }

    public byte[] exportSessionsCsv(Long operatorId, OpsSessionOrderQueryService.SessionExportQuery query) {
        return sessionOrderQueryService.exportSessionsCsv(operatorId, query);
    }

    public AdminTrendDto orderTrend(Long operatorId) {
        return analyticsQueryService.orderTrend(operatorId);
    }

    public AdminTrendDto orderTrend(Long operatorId, int days) {
        return analyticsQueryService.orderTrend(operatorId, days);
    }

    public AdminChannelBreakdownDto channelBreakdown(Long operatorId, int days) {
        return analyticsQueryService.channelBreakdown(operatorId, days);
    }

    public AdminOpsTrendDto opsTrend(Long operatorId) {
        return analyticsQueryService.opsTrend(operatorId);
    }

    public AdminOpsTrendDto opsTrend(Long operatorId, int days) {
        return analyticsQueryService.opsTrend(operatorId, days);
    }

    @Transactional
    public AdminUserDto adjustBalance(Long operatorId, Long userId, AdjustBalanceRequest request) {
        return memberFinanceAdminService.adjustBalance(operatorId, userId, request);
    }

    /** 消费者余额锁 key（门面兼容入口）。 */
    public static String userBalanceLockKey(long userId) {
        return OpsMemberFinanceAdminService.userBalanceLockKey(userId);
    }

    @Transactional
    public AdminUserDto setUserVerified(Long operatorId, Long userId, VerifyUserRequest request) {
        return memberFinanceAdminService.setUserVerified(operatorId, userId, request);
    }

    @Transactional(readOnly = true)
    public PageResult<RechargeOrderDto> listRecharges(Long operatorId, int page, int size,
                                                      String status, Long userId) {
        return memberFinanceAdminService.listRecharges(operatorId, page, size, status, userId);
    }

    @Transactional
    public RechargeOrderDto refundRecharge(Long operatorId, String orderId, String reason) {
        return memberFinanceAdminService.refundRecharge(operatorId, orderId, reason);
    }
}
