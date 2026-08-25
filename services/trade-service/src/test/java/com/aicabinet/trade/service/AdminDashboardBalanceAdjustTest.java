package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdjustBalanceRequest;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import com.aicabinet.trade.storage.MinioVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardBalanceAdjustTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DisputeTicketMapper disputeRepository;
    @Mock private SettlementService settlementService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private AdminAuditService auditService;
    @Mock private AdminAuditLogMapper auditLogRepository;
    @Mock private PermissionService permissionService;
    @Mock private PaymentService paymentService;
    @Mock private RechargeOrderMapper rechargeOrderRepository;
    @Mock private SlaMetricsService slaMetricsService;
    @Mock private MinioVideoService minioVideoService;
    @Mock private MerchantMapper merchantRepository;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private DisputeSlaService disputeSlaService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock private PaymentReconciliationMapper reconciliationRepository;
    @Mock private WarehouseInTransitMapper inTransitRepository;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private RefundPolicyService refundPolicyService;
    @Mock private OpsExceptionMapper exceptionRepository;
    @Mock private FileAttachmentService fileAttachmentService;
    @Mock private MemberMapper memberRepository;
    @Mock private UserBlacklistMapper blacklistRepository;
    @Mock private DistributedLockService distributedLockService;

    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        service = new AdminDashboardService(
                deviceRepository, sessionRepository, orderRepository, orderLineRepository,
                disputeRepository, settlementService, userInfoRepository, userAccountRepository,
                skuCatalogRepository, auditService, auditLogRepository, permissionService,
                paymentService, rechargeOrderRepository, slaMetricsService, minioVideoService,
                merchantRepository, merchantScopeService, inventoryRepository, splitRepository,
                disputeSlaService, inventoryLotService, deviceSlotService, replenishmentTaskRepository,
                reconciliationRepository, inTransitRepository, balanceLedgerService, refundPolicyService,
                exceptionRepository, fileAttachmentService, memberRepository, blacklistRepository,
                distributedLockService, null);
    }

    @Test
    void adjustBalance_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(AdminDashboardService.userBalanceLockKey(10001L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adjustBalance(1L, 10001L,
                        new AdjustBalanceRequest(100, "test adjust", "key-1")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void adjustBalance_whenUserNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(AdminDashboardService.userBalanceLockKey(10001L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.adjustBalance(1L, 10001L,
                        new AdjustBalanceRequest(100, "test adjust", "key-1")));

        verify(distributedLockService).unlock(AdminDashboardService.userBalanceLockKey(10001L));
    }
}
