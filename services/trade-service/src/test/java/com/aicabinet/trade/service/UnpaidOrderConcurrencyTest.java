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

    private UnpaidOrderService service;

    @BeforeEach
    void setUp() {
        service = new UnpaidOrderService(
                orderRepository, orderLineRepository, userInfoRepository, auditLogRepository,
                inventoryService, orderPaymentService, revenueSplitService, memberService,
                couponService, merchantScopeService, permissionService, auditService,
                riskControlService, systemConfigService, weChatMiniAppClient,
                weChatMiniAppProperties, settlementService, consumerPreauthService,
                notificationService, distributedLockService, apiRateLimitService);
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

    /** F1 防回归护栏：PENDING 单已有已完成支付流水（净额≠0）时，补扣必须 CONFLICT 且不触发扣款。 */
    @Test
    void collectByUser_whenNetAlreadyPaid_rejectsAndSkipsCharge() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-PAID-TWICE");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setSessionId("S-2");
        order.setTotalAmountCents(350);

        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-PAID-TWICE"), 60L, 5L))
                .thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-PAID-TWICE")).thenReturn(Optional.of(order));
        when(orderPaymentService.netCompletedCents("O-PAID-TWICE")).thenReturn(200);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.collectByUser(10001L, "O-PAID-TWICE", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        org.mockito.Mockito.verify(orderPaymentService, org.mockito.Mockito.never())
                .chargeOrder(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(distributedLockService).unlock(
                OrderPaymentService.orderPaymentLockKey("O-PAID-TWICE"));
    }
}
