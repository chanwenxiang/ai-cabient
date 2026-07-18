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
    @Mock DemoDataService demoDataService;
    @Mock DeviceValidationService deviceValidationService;
    @Mock MerchantSkuPricingService skuPricingService;
    @Mock UserValidationService userValidationService;
    @Mock VideoArchiveService videoArchiveService;
    @Mock SkuVisionEnrollmentService skuVisionEnrollmentService;
    @Mock CouponService couponService;
    @Mock MemberService memberService;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                sessionRepository, skuCatalogRepository, orderRepository, orderLineRepository,
                visionClient, disputeService, visionRecognitionProducer, revenueSplitService,
                securityProperties, stagingProperties, inventoryService, orderPaymentService, confidenceService, gravityHelper,
                demoDataService, deviceValidationService, skuPricingService, userValidationService, videoArchiveService,
                skuVisionEnrollmentService, couponService, memberService, null);
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
    void balanceInsufficient_beforeInventory_escalatesWithoutSideEffects() {
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
        when(gravityHelper.toRecognizedItems(any())).thenReturn(
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 2, 0.9f)));
        when(skuCatalogRepository.findById("SKU-DEMO-001")).thenReturn(java.util.Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-001", sku)).thenReturn(350);
        org.mockito.Mockito.doThrow(new BalanceInsufficientException(ApiMessages.INSUFFICIENT_BALANCE))
                .when(userValidationService).validateSufficientBalanceForCharge(10001L, 700);

        assertThrows(BalanceInsufficientException.class,
                () -> settlementService.processRecognitionResult(session,
                        new VisionServiceClient.RecognitionResult("T-1", List.of(), 0.9f, false, "mock", List.of())));

        verifyNoInteractions(inventoryService, orderPaymentService, revenueSplitService);
    }
}
