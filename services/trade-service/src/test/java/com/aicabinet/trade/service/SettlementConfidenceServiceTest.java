package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementConfidenceServiceTest {

    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock SystemConfigService systemConfigService;

    SettlementConfidenceService service;

    @BeforeEach
    void setUp() {
        service = new SettlementConfidenceService(skuCatalogRepository, systemConfigService);
        lenient().when(systemConfigService.getDouble(eq(SystemConfigService.SETTLEMENT_MIN_CONFIDENCE), anyDouble()))
                .thenReturn(0.72d);
    }

    @Test
    void overallBelowGlobalMin_triggersReview() {
        var item = new VisionServiceClient.RecognizedItem("SKU-1", 1, 0.95f);
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(item), 0.60f, false, "yolo", List.of());

        String reason = service.reviewReasonIfNeeded(recognition);

        assertNotNull(reason);
        assertTrue(reason.contains("整体识别置信度"));
        assertTrue(reason.contains("72%"));
    }

    @Test
    void skuBelowChargeThreshold_triggersReview() {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-1");
        sku.setSkuName("可乐");
        sku.setMinChargeConfidence(0.92f);
        when(skuCatalogRepository.findAllById(List.of("SKU-1"))).thenReturn(List.of(sku));

        var item = new VisionServiceClient.RecognizedItem("SKU-1", 1, 0.85f);
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(item), 0.90f, false, "yolo", List.of());

        String reason = service.reviewReasonIfNeeded(recognition);

        assertNotNull(reason);
        assertTrue(reason.contains("可乐"));
        assertTrue(reason.contains("扣款阈值"));
    }

    @Test
    void highConfidence_passes() {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-1");
        sku.setSkuName("可乐");
        sku.setMinChargeConfidence(0.92f);
        when(skuCatalogRepository.findAllById(List.of("SKU-1"))).thenReturn(List.of(sku));

        var item = new VisionServiceClient.RecognizedItem("SKU-1", 1, 0.97f);
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(item), 0.95f, false, "yolo", List.of());

        assertNull(service.reviewReasonIfNeeded(recognition));
    }

    @Test
    void emptyItems_skips() {
        var recognition = new VisionServiceClient.RecognitionResult(
                "T-1", List.of(), 0.10f, false, "yolo", List.of());
        assertNull(service.reviewReasonIfNeeded(recognition));
    }
}
