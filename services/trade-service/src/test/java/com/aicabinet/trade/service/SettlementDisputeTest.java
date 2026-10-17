package com.aicabinet.trade.service;

import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.recognition.RecognitionOutcome;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.aicabinet.trade.support.ApiMessages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 结算决策管道（DC-27 批2 之后）：识别明细只认上报，重力/兜底标称一律进争议。
 */
@ExtendWith(MockitoExtension.class)
class SettlementDisputeTest {

    @Mock ShoppingSessionMapper sessionRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock com.aicabinet.trade.mapper.CabinetOrderLineMapper orderLineRepository;
    @Mock com.aicabinet.trade.mapper.DeviceSlotMapper slotRepository;
    @Mock DisputeService disputeService;
    @Mock SettlementVisionAsyncService settlementVisionAsyncService;
    @Mock RevenueSplitService revenueSplitService;
    @Mock SecurityProperties securityProperties;
    @Mock StagingProperties stagingProperties;
    @Mock InventoryService inventoryService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock SettlementConfidenceService confidenceService;
    @Mock DeviceValidationService deviceValidationService;
    @Mock MerchantSkuPricingService skuPricingService;
    @Mock UserValidationService userValidationService;
    @Mock VideoArchiveService videoArchiveService;
    @Mock CouponService couponService;
    @Mock MemberService memberService;
    @Mock NotificationService notificationService;
    @Mock ConsumerPreauthService consumerPreauthService;
    @Mock SystemConfigService systemConfigService;
    @Mock RecognitionResultWriter recognitionResultWriter;
    @Mock DistributedLockService distributedLockService;
    @Mock DisplaySnapshotHelper displaySnapshotHelper;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        var orderViewAssembler = new com.aicabinet.trade.service.view.OrderViewAssembler();
        SettlementOrderSupport orderSupport = new SettlementOrderSupport(
                skuCatalogRepository, orderRepository, orderLineRepository, slotRepository,
                skuPricingService, memberService, couponService, null,
                revenueSplitService, orderPaymentService, systemConfigService, orderViewAssembler);
        settlementService = new SettlementService(
                sessionRepository, orderRepository,
                settlementVisionAsyncService, null, null, null, null, null,
                orderSupport, distributedLockService);
        SettlementOrderFinalizeService finalizeSvc = new SettlementOrderFinalizeService(
                sessionRepository, orderRepository, orderLineRepository, deviceValidationService,
                inventoryService, orderPaymentService, userValidationService, couponService, memberService,
                revenueSplitService, notificationService, videoArchiveService, displaySnapshotHelper,
                orderSupport, org.mockito.Mockito.mock(OpsExceptionService.class),
                org.mockito.Mockito.mock(RiskControlService.class));
        SettlementRecognitionService recognitionSvc = new SettlementRecognitionService(
                sessionRepository, orderRepository, confidenceService,
                securityProperties, stagingProperties, systemConfigService,
                disputeService, consumerPreauthService, settlementService, orderSupport, recognitionResultWriter);
        SettlementSettleOrchestrator settleOrchestrator = new SettlementSettleOrchestrator(
                sessionRepository, orderRepository, deviceValidationService,
                recognitionSvc, settlementService, orderSupport, null);
        org.springframework.test.util.ReflectionTestUtils.setField(settleOrchestrator, "self", settleOrchestrator);
        org.springframework.test.util.ReflectionTestUtils.setField(
                settlementService, "settlementOrderFinalizeService", finalizeSvc);
        org.springframework.test.util.ReflectionTestUtils.setField(
                settlementService, "settleOrchestrator", settleOrchestrator);
        lenient().when(systemConfigService.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        lenient().when(sessionRepository.findByIdForUpdate(anyString())).thenAnswer(inv -> {
            ShoppingSession s = new ShoppingSession();
            s.setSessionId(inv.getArgument(0));
            return java.util.Optional.of(s);
        });
    }

    private static ShoppingSession session(String sessionId) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(sessionId);
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");
        return session;
    }

    private static SkuCatalog colaSku() {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-DEMO-001");
        sku.setSkuName("可乐");
        sku.setPriceCents(350);
        return sku;
    }

    /** 端侧上报的可乐 x2（单价 350 → 合计 700）。 */
    private static List<RecognizedItem> twoColas() {
        return List.of(new RecognizedItem("SKU-DEMO-001", 2, 0.95f));
    }

    /** 落单链上必到的那几个协作者——按 settle 还是 unpaid 分组 stub，避免每个用例复制十行。 */
    private void stubSettleCollaborators() {
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void emptyRecognition_escalatesToDispute_notFailed() {
        ShoppingSession session = session("S-TEST-001");

        when(orderRepository.findBySessionId("S-TEST-001")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(stagingProperties.stagingMode()).thenReturn(false);

        var recognition = new RecognitionOutcome(
                "T-1", List.of(), 0f, false, "cabinet-skus-v1", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(session, recognition,
                "未识别到商品，需人工审核");
    }

    /**
     * 识别结果必须被落库。{@code recognition_result} 自 V1 建表起长期无写入者（0 行），
     * 本批在结算识别收敛处补上唯一写入点；此用例守住「钩子真的被结算路径调到」，
     * 且落库发生在争议闸<b>之前</b>——转人工的会话同样要有识别留痕可对账。
     */
    @Test
    void recognitionApplied_persistsResultBeforeDisputeGate() {
        ShoppingSession session = session("S-PERSIST-01");

        when(orderRepository.findBySessionId("S-PERSIST-01")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(stagingProperties.stagingMode()).thenReturn(false);

        var recognition = new RecognitionOutcome(
                "T-persist", List.of(), 0f, false, "cabinet-skus-v1", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(recognitionResultWriter).persist(eq("S-PERSIST-01"), eq(recognition));
    }

    /** 政策开关打开（未取货/拿了又放回）→ 零结 PAID，不进争议。 */
    @Test
    void emptyRecognition_withAutoCompleteConfig_zeroSettles() {
        ShoppingSession session = session("S-EMPTY-AUTO");

        when(orderRepository.findBySessionId("S-EMPTY-AUTO")).thenReturn(java.util.Optional.empty());
        when(systemConfigService.getBoolean(
                SystemConfigService.SETTLEMENT_EMPTY_AUTO_COMPLETE, false)).thenReturn(true);
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(true);
        stubSettleCollaborators();

        var recognition = new RecognitionOutcome(
                "T-empty", List.of(), 0f, false, "cabinet-skus-v1", List.of());

        var order = settlementService.processRecognitionResult(session, recognition, true);
        assertNotNull(order);
        assertEquals(0, order.totalAmountCents());
        verifyNoInteractions(disputeService);
    }

    /** 会话上残留的重力字段不再参与判定：空明细 + 政策关闭 → 依旧进争议。 */
    @Test
    void emptyRecognition_withLegacyGravityJson_stillEscalates() {
        ShoppingSession session = session("S-VISION-EMPTY");
        session.setGravityDeltas("[]");

        when(orderRepository.findBySessionId("S-VISION-EMPTY")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(stagingProperties.stagingMode()).thenReturn(false);

        var recognition = new RecognitionOutcome(
                "T-ve", List.of(), 0f, false, "cabinet-skus-v1", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(session, recognition,
                "未识别到商品，需人工审核");
    }

    @Test
    void legacyClose_cloudVisionRetired_escalatesWithoutCharging() {
        // 旧云识别已退役（2026-10-11）：关门结算不再调用任何识别引擎，
        // 占位「待人工审核」结果必须仍走 fail-closed 争议，且零资金移动。
        ShoppingSession session = session("S-VISION-DOWN");

        when(orderRepository.findBySessionId("S-VISION-DOWN")).thenReturn(java.util.Optional.empty());

        assertThrows(DisputeRequiredException.class, () -> settlementService.settle(session));

        org.mockito.ArgumentCaptor<RecognitionOutcome> captor =
                org.mockito.ArgumentCaptor.forClass(RecognitionOutcome.class);
        verify(disputeService).createTicket(eq(session), captor.capture(), anyString());
        assertEquals("cloud-vision-retired", captor.getValue().modelVersion());
        verifyNoInteractions(orderPaymentService, inventoryService, revenueSplitService);
    }

    /**
     * 负向铁律：标称非生产精度的上报（mock / fallback / gravity-*）一律进争议、零资金移动。
     * 这些标称的生产者已随 DC-27 批2 删除，此处守的是「外部上报或历史重放带这些标称时不得静默扣款」。
     */
    @Test
    void nonProductionModelVersion_alwaysEscalates() {
        Map<String, String> reasonByVersion = new LinkedHashMap<>();
        reasonByVersion.put("mock-v1", "模拟/兜底识别结果，非生产精度，需人工审核");
        reasonByVersion.put("staging-fallback", "模拟/兜底识别结果，非生产精度，需人工审核");
        reasonByVersion.put("cabinet-skus-v1+gravity-mismatch", "视觉与重力数量不一致，需人工审核");
        reasonByVersion.put("gravity-fill", "视觉为空，仅有重力信号（非生产识别精度），需人工审核");

        int idx = 0;
        for (var entry : reasonByVersion.entrySet()) {
            String sessionId = "S-NONPROD-" + (++idx);
            ShoppingSession session = session(sessionId);
            when(orderRepository.findBySessionId(sessionId)).thenReturn(java.util.Optional.empty());

            var items = List.of(new RecognizedItem("SKU-DEMO-001", 1, 0.92f));
            var recognition = new RecognitionOutcome(
                    "T-" + sessionId, items, 0.92f, false, entry.getKey(), List.of());

            assertThrows(DisputeRequiredException.class,
                    () -> settlementService.processRecognitionResult(session, recognition, true),
                    "标称 " + entry.getKey() + " 不得静默结算");
            verify(disputeService).createTicket(eq(session), any(RecognitionOutcome.class),
                    eq(entry.getValue()));
        }
        verifyNoInteractions(orderPaymentService, inventoryService, revenueSplitService);
    }

    @Test
    void balanceInsufficient_createsUnpaidOrder_withoutPaymentCharge() {
        ShoppingSession session = session("S-BAL-SETTLE");
        session.setUserId(10001L);
        SkuCatalog sku = colaSku();

        when(orderRepository.findBySessionId("S-BAL-SETTLE")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        when(memberService.applyMemberPriceDiscount(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(false);
        doThrow(new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE))
                .when(userValidationService).validateSufficientBalanceForCharge(eq(10001L), eq(700), anyInt());
        stubSettleCollaborators();

        var order = settlementService.processRecognitionResult(session,
                new RecognitionOutcome("T-1", twoColas(), 0.95f, false, "cabinet-skus-v1", List.of()));

        assertEquals("PENDING", order.status());
        assertEquals(700, order.totalAmountCents());
        // toDto 会读已完成扣款/退款分位，不等于发起扣款
        verify(orderPaymentService, never()).chargeOrder(any());
        verify(orderPaymentService, never()).chargeOrder(any(), any());
    }

    /**
     * M5: 余额不足转 PENDING —— 已扣库存、不 markUsed、无 CHARGE/分账。
     */
    @Test
    void unpaidPending_deductsInventory_skipsCouponMarkAndCharge() {
        ShoppingSession session = session("S-M5");
        session.setUserId(10001L);
        SkuCatalog sku = colaSku();

        when(orderRepository.findBySessionId("S-M5")).thenReturn(java.util.Optional.empty());
        when(sessionRepository.findById("S-M5")).thenReturn(java.util.Optional.of(session));
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        when(memberService.applyMemberPriceDiscount(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(couponService.selectPreferredOrBest(eq(10001L), any(), eq(700), any()))
                .thenReturn(java.util.Optional.of(new CouponService.BestCoupon(42L, 100, "满减券")));
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(false);
        // 折后应付 600 仍不足
        doThrow(new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE))
                .when(userValidationService).validateSufficientBalanceForCharge(eq(10001L), eq(600), anyInt());
        when(inventoryService.deductForOrder(any(), any(), any()))
                .thenReturn(java.util.Map.of("SKU-DEMO-001", "BATCH-1"));
        stubSettleCollaborators();

        var order = settlementService.processRecognitionResult(session,
                new RecognitionOutcome("T-m5", twoColas(), 0.95f, false, "cabinet-skus-v1", List.of()));

        assertEquals("PENDING", order.status());
        assertEquals(700, order.totalAmountCents());
        verify(inventoryService).deductForOrder(eq("CAB-001"), any(), eq(order.orderId()));
        verify(couponService, never()).markUsed(any(), any(), any(), any(), anyInt());
        verify(orderPaymentService, never()).chargeOrder(any());
        verify(revenueSplitService, never()).recordSplit(any());
        verify(orderRepository).save(argThat(o ->
                "PENDING".equals(o.getStatus())
                        && o.isInventoryDeducted()
                        && o.getCouponId() == null
                        && o.getCouponDiscountCents() == 0
                        && o.getTotalAmountCents() == 700));
    }

    /**
     * M6: 预检余额够，charge 中途 412 → PENDING；已扣库、清券、不 markUsed/不分账。
     */
    @Test
    void chargeMidwayInsufficient_convertsToPending_clearsCoupon() {
        ShoppingSession session = session("S-M6");
        session.setUserId(10001L);
        SkuCatalog sku = colaSku();

        when(orderRepository.findBySessionId("S-M6")).thenReturn(java.util.Optional.empty());
        when(sessionRepository.findById("S-M6")).thenReturn(java.util.Optional.of(session));
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        when(memberService.applyMemberPriceDiscount(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(couponService.selectPreferredOrBest(eq(10001L), any(), eq(700), any()))
                .thenReturn(java.util.Optional.of(new CouponService.BestCoupon(7L, 100, "券")));
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(false);
        // 预检通过（折后 600）
        org.mockito.Mockito.doNothing().when(userValidationService)
                .validateSufficientBalanceForCharge(eq(10001L), eq(600), anyInt());
        when(inventoryService.deductForOrder(any(), any(), any()))
                .thenReturn(java.util.Map.of("SKU-DEMO-001", "BATCH-1"));
        stubSettleCollaborators();
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.PRECONDITION_FAILED, "可用余额不足"))
                .when(orderPaymentService).chargeOrder(any());

        var order = settlementService.processRecognitionResult(session,
                new RecognitionOutcome("T-m6", twoColas(), 0.95f, false, "cabinet-skus-v1", List.of()));

        assertEquals("PENDING", order.status());
        assertEquals(700, order.totalAmountCents());
        verify(orderPaymentService).chargeOrder(any());
        verify(couponService, never()).markUsed(any(), any(), any(), any(), anyInt());
        verify(revenueSplitService, never()).recordSplit(any());
        verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(argThat(o ->
                "PENDING".equals(o.getStatus())
                        && o.isInventoryDeducted()
                        && o.getCouponId() == null
                        && o.getTotalAmountCents() == 700));
    }

    /**
     * M3: 生产关闭 mock、need_review → 争议；释放预授权；不扣库不扣款。
     */
    @Test
    void needReview_production_escalatesAndReleasesPreauth() {
        ShoppingSession session = session("S-M3");
        session.setUserId(10001L);
        session.setPreauthStatus(ConsumerPreauthService.STATUS_FROZEN);
        session.setPreauthCents(500);

        when(orderRepository.findBySessionId("S-M3")).thenReturn(java.util.Optional.empty());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var items = List.of(new RecognizedItem("SKU-DEMO-001", 1, 0.5f));
        var recognition = new RecognitionOutcome(
                "T-m3", items, 0.5f, true, "cabinet-skus-v1", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, false));

        verify(consumerPreauthService).releaseIfFrozen(session);
        verify(disputeService).createTicket(eq(session), any(RecognitionOutcome.class),
                eq("识别结果需人工审核"));
        verifyNoInteractions(orderPaymentService, inventoryService, revenueSplitService);
        verify(couponService, never()).markUsed(any(), any(), any(), any(), anyInt());
    }

    /**
     * M4: 高置信自动结算 → PAID；扣库、核销券、分账、调用 charge（预授权由 charge 路径冲抵）。
     */
    @Test
    void highConfidenceFinalize_paid_deductsMarksCouponAndSplits() {
        ShoppingSession session = session("S-M4");
        session.setUserId(10001L);
        session.setPreauthStatus(ConsumerPreauthService.STATUS_FROZEN);
        session.setPreauthCents(500);

        SkuCatalog sku = colaSku();

        when(orderRepository.findBySessionId("S-M4")).thenReturn(java.util.Optional.empty());
        when(sessionRepository.findById("S-M4")).thenReturn(java.util.Optional.of(session));
        when(confidenceService.reviewReasonIfNeeded(any())).thenReturn(null);
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        when(memberService.applyMemberPriceDiscount(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(couponService.selectPreferredOrBest(eq(10001L), any(), eq(700), any()))
                .thenReturn(java.util.Optional.of(new CouponService.BestCoupon(99L, 100, "满减")));
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(false);
        org.mockito.Mockito.doNothing().when(userValidationService)
                .validateSufficientBalanceForCharge(eq(10001L), eq(600), anyInt());
        when(inventoryService.deductForOrder(any(), any(), any()))
                .thenReturn(java.util.Map.of("SKU-DEMO-001", "BATCH-M4"));
        stubSettleCollaborators();
        when(revenueSplitService.recordSplit(any())).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.doNothing().when(couponService)
                .markUsed(eq(10001L), eq(99L), anyString(), eq("CAB-001"), eq(100));

        var items = List.of(new RecognizedItem("SKU-DEMO-001", 2, 0.97f));
        var recognition = new RecognitionOutcome(
                "T-m4", items, 0.97f, false, "cabinet-skus-v1", List.of("bottle"));

        var order = settlementService.processRecognitionResult(session, recognition, false);

        assertEquals("PAID", order.status());
        assertEquals(600, order.totalAmountCents());
        assertEquals(100, order.couponDiscountCents());
        verifyNoInteractions(disputeService);
        verify(inventoryService).deductForOrder(eq("CAB-001"), any(), eq(order.orderId()));
        verify(orderPaymentService).chargeOrder(argThat(o ->
                "PAID".equals(o.getStatus())
                        && o.getCouponId() != null
                        && o.getCouponId() == 99L
                        && o.getTotalAmountCents() == 600
                        && o.isInventoryDeducted()));
        verify(couponService).markUsed(eq(10001L), eq(99L), anyString(), eq("CAB-001"), eq(100));
        verify(revenueSplitService).recordSplit(argThat(o ->
                "PAID".equals(o.getStatus()) && o.getTotalAmountCents() == 600));
        verify(consumerPreauthService, never()).releaseIfFrozen(any());
    }

    /**
     * 会话上残留的 gravityDeltas 只是历史取证列：结算明细与扣库明细都必须来自上报，
     * 重力列写什么都不影响金额（曾经的「写购物车进重力列即凑出 PAID」通路已删）。
     */
    @Test
    void legacyGravityColumn_isIgnored_settlesOnReportedItems() {
        ShoppingSession session = session("S-VISION-ONLY");
        session.setUserId(10001L);
        session.setGravityDeltas("[{\"skuId\":\"SKU-DEMO-001\",\"delta\":-1}]");
        SkuCatalog sku = colaSku();

        when(orderRepository.findBySessionId("S-VISION-ONLY")).thenReturn(java.util.Optional.empty());
        when(sessionRepository.findById("S-VISION-ONLY")).thenReturn(java.util.Optional.of(session));
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        when(memberService.applyMemberPriceDiscount(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(true);
        stubSettleCollaborators();

        var recognition = new RecognitionOutcome(
                "T-vision", twoColas(), 0.95f, false, "cabinet-skus-v1", List.of("bottle"));

        var order = settlementService.processRecognitionResult(session, recognition, true);

        assertEquals("PAID", order.status());
        assertEquals(700, order.totalAmountCents());
        verifyNoInteractions(disputeService);
        verify(inventoryService).deductForOrder(
                eq("CAB-001"), eq(twoColas()), eq(order.orderId()));
    }
}
