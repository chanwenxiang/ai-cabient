package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementDisputeTest {

    @Mock ShoppingSessionMapper sessionRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock com.aicabinet.trade.mapper.CabinetOrderLineMapper orderLineRepository;
    @Mock VisionServiceClient visionClient;
    @Mock DisputeService disputeService;
    @Mock ObjectProvider<com.aicabinet.trade.messaging.VisionRecognitionProducer> visionRecognitionProducer;
    @Mock RevenueSplitService revenueSplitService;
    @Mock SecurityProperties securityProperties;
    @Mock StagingProperties stagingProperties;
    @Mock InventoryService inventoryService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock SettlementConfidenceService confidenceService;
    @Mock GravitySettlementHelper gravityHelper;
    @Mock DeviceValidationService deviceValidationService;
    @Mock MerchantSkuPricingService skuPricingService;
    @Mock UserValidationService userValidationService;
    @Mock VideoArchiveService videoArchiveService;
    @Mock SkuVisionEnrollmentService skuVisionEnrollmentService;
    @Mock CouponService couponService;
    @Mock MemberService memberService;
    @Mock NotificationService notificationService;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                sessionRepository, skuCatalogRepository, orderRepository, orderLineRepository,
                visionClient, disputeService, visionRecognitionProducer, revenueSplitService,
                securityProperties, stagingProperties, inventoryService, orderPaymentService, confidenceService, gravityHelper,
                deviceValidationService, skuPricingService, userValidationService, videoArchiveService,
                skuVisionEnrollmentService, couponService, memberService, null, notificationService);
    }

    @Test
    void emptyRecognition_escalatesToDispute_notFailed() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-TEST-001");
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");

        when(orderRepository.findBySessionId("S-TEST-001")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(stagingProperties.stagingMode()).thenReturn(false);
        when(gravityHelper.reconcileWithGravity(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(), 0f, false, "yolov8", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(eq(session), eq(recognition),
                eq("未识别到商品，需人工审核"));
    }

    @Test
    void nonProductionSku_escalatesToDispute() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-WHITELIST");
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");

        when(orderRepository.findBySessionId("S-WHITELIST")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(gravityHelper.reconcileWithGravity(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(confidenceService.reviewReasonIfNeeded(any())).thenReturn(null);
        when(skuVisionEnrollmentService.validateSettlementItems(eq("CAB-001"), any()))
                .thenReturn(java.util.Optional.of("SKU SKU-X 视觉状态为 MAPPING，不可自动扣款"));

        var items = List.of(new VisionServiceClient.RecognizedItem("SKU-X", 1, 0.95f));
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", items, 0.95f, false, "cabinet-skus-v1", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(eq(session), eq(recognition),
                eq("SKU SKU-X 视觉状态为 MAPPING，不可自动扣款"));
    }

    @Test
    void visionUnavailable_inMockMode_escalatesWithoutCharging() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-VISION-DOWN");
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");

        when(orderRepository.findBySessionId("S-VISION-DOWN")).thenReturn(java.util.Optional.empty());
        when(visionClient.recognize(session)).thenThrow(new ResourceAccessException("vision down"));

        assertThrows(DisputeRequiredException.class, () -> settlementService.settle(session));

        verify(disputeService).createTicket(eq(session), any(VisionServiceClient.RecognitionResult.class),
                eq("识别服务暂时不可用，已转人工审核，本次暂未扣款"));
        verifyNoInteractions(orderPaymentService, inventoryService, revenueSplitService);
    }

    @Test
    void balanceInsufficient_createsUnpaidOrder_withoutPaymentCharge() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-BAL-SETTLE");
        session.setUserId(10001L);
        session.setDeviceId("CAB-001");

        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-DEMO-001");
        sku.setSkuName("可乐");
        sku.setPriceCents(350);

        when(orderRepository.findBySessionId("S-BAL-SETTLE")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(gravityHelper.reconcileWithGravity(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(gravityHelper.toRecognizedItems(any())).thenReturn(
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 2, 0.9f)));
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        when(userValidationService.canChargeViaPasswordFree(any(), any())).thenReturn(false);
        org.mockito.Mockito.doThrow(new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE))
                .when(userValidationService).validateSufficientBalanceForCharge(10001L, 700, 0);
        when(inventoryService.deductForOrder(any(), any(), any(), any())).thenReturn(java.util.Map.of());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var order = settlementService.processRecognitionResult(session,
                new VisionServiceClient.RecognitionResult("T-1", List.of(), 0.9f, false, "yolov8", List.of()));

        org.junit.jupiter.api.Assertions.assertEquals("PENDING", order.status());
        verifyNoInteractions(orderPaymentService, revenueSplitService);
    }

    @Test
    void mockModelVersion_escalatesToDispute_evenInMockMode() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-MOCK-GATE");
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");

        when(orderRepository.findBySessionId("S-MOCK-GATE")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(gravityHelper.reconcileWithGravity(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        var items = List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 0.92f));
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-mock", items, 0.92f, false, "mock-v1", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(eq(session), any(VisionServiceClient.RecognitionResult.class),
                eq("模拟/兜底识别结果，非生产精度，需人工审核"));
        verifyNoInteractions(orderPaymentService, inventoryService);
    }

    @Test
    void gravityMismatch_escalatesEvenWithStagingGravitySettle() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-MISMATCH");
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");
        session.setGravityDeltas("[{\"skuId\":\"SKU-DEMO-001\",\"delta\":-1}]");

        when(orderRepository.findBySessionId("S-MISMATCH")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(false);
        when(gravityHelper.reconcileWithGravity(any(), any())).thenAnswer(inv -> {
            VisionServiceClient.RecognitionResult vision = inv.getArgument(1);
            return new VisionServiceClient.RecognitionResult(
                    vision.taskId(), vision.items(), vision.overallConfidence(), true,
                    vision.modelVersion() + "+gravity-mismatch", vision.detectedClasses());
        });

        var items = List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 2, 0.95f));
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-2", items, 0.95f, false, "yolov8", List.of("bottle"));

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(eq(session), any(VisionServiceClient.RecognitionResult.class),
                eq("视觉与重力数量不一致，需人工审核"));
        verifyNoInteractions(orderPaymentService);
    }

    @Test
    void gravityFill_escalatesToDispute_noSilentCharge() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-GRAVITY-FILL");
        session.setUserId(13800138000L);
        session.setDeviceId("CAB-001");

        when(orderRepository.findBySessionId("S-GRAVITY-FILL")).thenReturn(java.util.Optional.empty());
        when(securityProperties.mockEnabled()).thenReturn(true);
        when(gravityHelper.reconcileWithGravity(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        var items = List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 0.9f));
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-gf", items, 0.9f, true, "gravity-fill", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(eq(session), any(VisionServiceClient.RecognitionResult.class),
                eq("视觉为空，仅有重力信号（非生产识别精度），需人工审核"));
        verifyNoInteractions(orderPaymentService, inventoryService);
    }
}
