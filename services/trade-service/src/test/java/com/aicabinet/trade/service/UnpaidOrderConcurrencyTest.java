package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    /** V325：欠款催缴阶梯（CB-011）。用 mock 避免本测试关心短信副作用。 */
    @Mock private UnpaidDunningService unpaidDunningService;

    private UnpaidOrderService service;

    @BeforeEach
    void setUp() {
        TrackingTxManager.ACTIVE.set(false);
        TrackingTxManager.CHARGE_IN_TX.remove();
        TrackingTxManager.SAVE_IN_TX.set(null);
        service = new UnpaidOrderService(
                orderRepository, orderLineRepository, userInfoRepository, auditLogRepository,
                inventoryService, orderPaymentService, revenueSplitService, memberService,
                couponService, merchantScopeService, permissionService, auditService,
                riskControlService, systemConfigService, weChatMiniAppClient,
                weChatMiniAppProperties, settlementService, consumerPreauthService,
                notificationService, distributedLockService, apiRateLimitService,
                opsExceptionService, unpaidDunningService, new TrackingTxManager());
    }

    /** B1-B 形态钉：补缴入口不得再挂外层事务（否则行锁又会横跨渠道 HTTP）。 */
    @Test
    void collectEntrypoints_haveNoOuterTransaction() throws Exception {
        assertNull(UnpaidOrderService.class.getMethod("collect", Long.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        assertNull(UnpaidOrderService.class.getMethod("collectByUser", Long.class, String.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
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

    // ── MA-154：支付入口的归属闸（钱会动的那条；此前只钉过「锁忙」与本人正向）──────

    @Test
    void collectByUser_foreignUser_notFoundAndNothingCharged() {
        CabinetOrder other = new CabinetOrder();
        other.setOrderId("O-OWN");
        other.setUserId(20002L);
        other.setStatus("PENDING");
        other.setSessionId("S-OWN");
        other.setTotalAmountCents(500);
        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-OWN"), 60L, 5L)).thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-OWN")).thenReturn(Optional.of(other));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.collectByUser(10001L, "O-OWN", null));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode(),
                "他人订单要回 404（不确认订单号存在），而且绝不能走到扣款");
        verify(orderLineRepository, never()).findByOrderId("O-OWN");
        verify(orderRepository, never()).save(any(CabinetOrder.class));
    }

    @Test
    void collectByUser_owner_passesOwnershipGate() {
        // 正向对照：本人过得了归属门——用「状态非 PENDING」的 409 证明失败原因不是归属，
        // 否则「闸写反成只放行非本人」也能让上一条保持绿
        CabinetOrder mine = new CabinetOrder();
        mine.setOrderId("O-MINE");
        mine.setUserId(10001L);
        mine.setStatus("PAID");
        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-MINE"), 60L, 5L)).thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-MINE")).thenReturn(Optional.of(mine));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.collectByUser(10001L, "O-MINE", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), "应停在状态门而不是归属门");
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
        when(couponService.selectBestCoupon(eq(10001L), eq(500), any())).thenReturn(java.util.Optional.empty());
        when(settlementService.getOrderBySession("S-1")).thenReturn(null);

        service.collectByUser(10001L, "O-OK", null);

        org.mockito.Mockito.verify(orderRepository, org.mockito.Mockito.atLeastOnce())
                .findByIdForUpdate("O-OK");
        org.mockito.Mockito.verify(distributedLockService).unlock(OrderPaymentService.orderPaymentLockKey("O-OK"));
    }

    /**
     * CB-082：活动价 PENDING 单补扣时不得再叠券——与结算主链同口径（活动优先）。
     */
    @Test
    void collectByUser_activityOrder_doesNotStackCoupon() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-ACT");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setSessionId("S-ACT");
        order.setDeviceId("CAB-ACT");
        order.setActivityDiscountCents(150);
        order.setTotalAmountCents(1026);
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId("SKU-A");
        line.setQuantity(3);
        line.setUnitPriceCents(342);
        line.setLineAmountCents(1026);
        order.setLines(new java.util.ArrayList<>(List.of(line)));

        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-ACT"), 60L, 5L))
                .thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-ACT")).thenReturn(Optional.of(order));
        when(orderPaymentService.netCompletedCents("O-ACT")).thenReturn(0);
        when(settlementService.getOrderBySession("S-ACT")).thenReturn(null);

        service.collectByUser(10001L, "O-ACT", null);

        verify(couponService, never()).selectBestCoupon(any(), anyInt(), any());
        assertEquals(1026, order.getTotalAmountCents(), "活动价必须保持，不得再减券");
        assertEquals(0, order.getCouponDiscountCents());
        assertNull(order.getCouponId());
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
    /** B1-B1 结构验证：prepare/finalize 在 tx 内，渠道 HTTP 在 tx 外（真栈 pg_locks 需真实慢渠道）。 */
    @Test
    void channelCharge_runsOutsideFinalizeTransaction() {
        CabinetOrder order = reconcileOrder(100);
        order.setStatus("PENDING");
        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-R"), 60L, 5L)).thenReturn(true);
        when(orderRepository.findByIdForUpdate("O-R")).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            TrackingTxManager.SAVE_IN_TX.compareAndSet(null, TrackingTxManager.ACTIVE.get());
            return null;
        });
        when(orderPaymentService.resolveChargePlan(any(), any())).thenReturn(
                OrderPaymentService.ChargePlan.of(OrderPaymentService.ChargeLeg.CHANNEL, "WECHAT"));
        org.mockito.Mockito.doAnswer(inv -> {
            TrackingTxManager.CHARGE_IN_TX.set(TrackingTxManager.ACTIVE.get());
            return null;
        }).when(orderPaymentService).chargeOrderWithPlan(any(), any(), any());

        service.collectByUser(10001L, "O-R", null);

        assertTrue(Boolean.TRUE.equals(TrackingTxManager.SAVE_IN_TX.get()), "选券/收口必须在事务内");
        assertFalse(Boolean.TRUE.equals(TrackingTxManager.CHARGE_IN_TX.get()), "渠道调用必须在事务外");
    }

    /** 内存事务管理器：只为观察回调时是否处于事务内，不接真实 DataSource。 */
    static final class TrackingTxManager implements org.springframework.transaction.PlatformTransactionManager {
        static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
        static final ThreadLocal<Boolean> CHARGE_IN_TX = new ThreadLocal<>();
        static final java.util.concurrent.atomic.AtomicReference<Boolean> SAVE_IN_TX =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            ACTIVE.set(true);
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            ACTIVE.set(false);
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            ACTIVE.set(false);
        }
    }

    /** B1-B2：reconciler 查到 SUCCESS ⇒ 补记流水 + 收口 PAID。 */
    @Test
    void reconcileChargePending_success_finalizesPaid() {
        CabinetOrder order = reconcileOrder(100);
        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-R"), 60L, 5L)).thenReturn(true);
        when(orderPaymentService.hasChargePending("O-R")).thenReturn(true);
        when(orderRepository.findById("O-R")).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate("O-R")).thenReturn(Optional.of(order));

        boolean ok = service.reconcileChargePending("O-R", 100, "WECHAT", 40, "TXN-R");

        assertTrue(ok);
        assertEquals("PAID", order.getStatus());
        verify(orderPaymentService).recordReconciledCharge(order, 100, "WECHAT", 40, "TXN-R");
        verify(distributedLockService).unlock(OrderPaymentService.orderPaymentLockKey("O-R"));
    }

    /** B1-B2：总额与痕迹不一致 ⇒ 只记流水/终结痕迹 + 告警，不擅自置 PAID。 */
    @Test
    void reconcileChargePending_amountMismatch_alertsWithoutMarkingPaid() {
        CabinetOrder order = reconcileOrder(90);
        when(distributedLockService.tryLock(
                OrderPaymentService.orderPaymentLockKey("O-R"), 60L, 5L)).thenReturn(true);
        when(orderPaymentService.hasChargePending("O-R")).thenReturn(true);
        when(orderRepository.findById("O-R")).thenReturn(Optional.of(order));

        boolean ok = service.reconcileChargePending("O-R", 100, "WECHAT", 40, "TXN-R");

        assertFalse(ok);
        assertEquals("PENDING", order.getStatus());
        verify(orderPaymentService).recordReconciledCharge(order, 100, "WECHAT", 40, "TXN-R");
        verify(orderPaymentService).finalizeChargePending("O-R", "TXN-R");
        verify(opsExceptionService).report(eq("CHARGE_PENDING_RECONCILE_MANUAL"), eq("HIGH"),
                any(), eq("渠道扣款成功但无法自动收口"), contains("订单总额"));
    }

    private static CabinetOrder reconcileOrder(int totalCents) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-R");
        order.setUserId(10001L);
        order.setStatus("PENDING");
        order.setSessionId("S-R");
        order.setDeviceId("DEV-R");
        order.setTotalAmountCents(totalCents);
        return order;
    }

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
        when(couponService.selectBestCoupon(eq(10001L), eq(350), any())).thenReturn(java.util.Optional.empty());

        service.collectByUser(10001L, "O-NET-PART", null);

        assertEquals("PAID", order.getStatus());
        org.mockito.Mockito.verify(orderPaymentService, org.mockito.Mockito.times(1))
                .chargeOrder(order, null);
    }
}
