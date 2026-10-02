package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnpaidOrderConcurrencyTest {

    @Mock private CabinetOrderMapper orderRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private AdminAuditLogMapper auditLogRepository;
    @Mock private InventoryService inventoryService;
    @Mock private OrderPaymentService orderPaymentService;
    @Mock private RevenueSplitService revenueSplitService;
    @Mock private MemberService memberService;
    @Mock private CouponService couponService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private RiskControlService riskControlService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private WeChatMiniAppClient weChatMiniAppClient;
    @Mock private WeChatMiniAppProperties weChatMiniAppProperties;
    @Mock private SettlementService settlementService;
    @Mock private ConsumerPreauthService consumerPreauthService;
    @Mock private NotificationService notificationService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApiRateLimitService apiRateLimitService;
    @Mock private OpsExceptionService opsExceptionService;

    private UnpaidOrderService service;

    @BeforeEach
    void setUp() {
        service = new UnpaidOrderService(
                orderRepository, orderLineRepository, userInfoRepository, auditLogRepository,
                inventoryService, orderPaymentService, revenueSplitService, memberService,
                couponService, merchantScopeService, permissionService, auditService,
                riskControlService, systemConfigService, weChatMiniAppClient,
                weChatMiniAppProperties, settlementService, consumerPreauthService,
                notificationService, distributedLockService, apiRateLimitService,
                opsExceptionService);
    }

    @Test
    void collectByUser_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-PEND"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.collectByUser(10001L, "O-PEND", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void collectByUser_acquiresLockAndLoadsOrderForUpdate() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-OK");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setSessionId("S-1");
        order.setTotalAmountCents(500);

        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-OK"), 60L, 5L))
                .thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-OK")).thenReturn(Optional.of(order));
        when(orderLineRepository.findByOrderId("O-OK")).thenReturn(java.util.List.of());
        when(couponService.selectBestCoupon(10001L, 500)).thenReturn(java.util.Optional.empty());
        when(settlementService.getOrderBySession("S-1")).thenReturn(null);

        service.collectByUser(10001L, "O-OK", null);

        org.mockito.Mockito.verify(orderRepository).findByIdForUpdate("O-OK");
        org.mockito.Mockito.verify(distributedLockService).unlock(OrderPaymentService.orderPaymentLockKey("O-OK"));
    }

    /** F1-C 净额三分支①：冲抵净额已覆盖应付 ⇒ 直接收口 PAID，绝不重复扣款。 */
    @Test
    void collectByUser_whenNetCoversFull_marksPaidWithoutCharge() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-NET-FULL");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setSessionId("S-2");
        order.setTotalAmountCents(350);

        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-NET-FULL"), 60L, 5L))
                .thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-NET-FULL")).thenReturn(Optional.of(order));
        when(orderPaymentService.netCompletedCents("O-NET-FULL")).thenReturn(350);

        service.collectByUser(10001L, "O-NET-FULL", null);

        assertEquals("PAID", order.getStatus(), "F1-C：净额覆盖应付必须直接收口");
        org.mockito.Mockito.verify(orderPaymentService, org.mockito.Mockito.never())
                .chargeOrder(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(distributedLockService).unlock(
                OrderPaymentService.orderPaymentLockKey("O-NET-FULL"));
    }

    /** F1-C 净额三分支②：净额部分覆盖（F1 竞态单）⇒ 委托 chargeOrder 按净额口径只补差额。 */
    @Test
    void collectByUser_whenNetPartial_delegatesRemainderCharge() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-NET-PART");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setSessionId("S-2");
        order.setTotalAmountCents(350);

        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-NET-PART"), 60L, 5L))
                .thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-NET-PART")).thenReturn(Optional.of(order));
        // 净额 200（F1 竞态保留的冲抵），差额 150 由 chargeOrder 净额口径补扣
        when(orderPaymentService.netCompletedCents("O-NET-PART")).thenReturn(200);
        when(couponService.selectBestCoupon(10001L, 350)).thenReturn(java.util.Optional.empty());

        service.collectByUser(10001L, "O-NET-PART", null);

        assertEquals("PAID", order.getStatus());
        org.mockito.Mockito.verify(orderPaymentService, org.mockito.Mockito.times(1))
                .chargeOrder(order, null);
    }
}
