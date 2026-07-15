package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.repository.SkuCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementDisputeTest {

    @Mock ShoppingSessionRepository sessionRepository;
    @Mock SkuCatalogRepository skuCatalogRepository;
    @Mock CabinetOrderRepository orderRepository;
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

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                sessionRepository, skuCatalogRepository, orderRepository,
                visionClient, disputeService, visionRecognitionProducer, revenueSplitService,
                securityProperties, stagingProperties, inventoryService, orderPaymentService, confidenceService, gravityHelper,
                demoDataService, deviceValidationService);
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
        when(gravityHelper.mergeWithVision(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(), 0f, false, "yolov8", List.of());

        assertThrows(DisputeRequiredException.class,
                () -> settlementService.processRecognitionResult(session, recognition, true));

        verify(disputeService).createTicket(eq(session), eq(recognition),
                eq("未识别到商品，需人工审核"));
    }
}
