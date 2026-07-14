package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GravitySettlementHelperTest {

    private GravitySettlementHelper helper;

    @BeforeEach
    void setUp() {
        helper = new GravitySettlementHelper(new ObjectMapper());
    }

    @Test
    void reconcile_usesVisionWhenPresent() {
        var vision = new VisionServiceClient.RecognitionResult(
                "T-1",
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 1, 0.9f)),
                0.9f,
                false,
                "yolov8-delta",
                List.of("bottle")
        );
        var result = helper.reconcileWithGravity(
                "[{\"skuId\":\"SKU-DEMO-001\",\"delta\":-1}]",
                vision
        );
        assertEquals(1, result.items().size());
        assertFalse(result.needReview());
        assertEquals("yolov8-delta", result.modelVersion());
    }

    @Test
    void reconcile_mismatchForcesReview() {
        var vision = new VisionServiceClient.RecognitionResult(
                "T-2",
                List.of(new VisionServiceClient.RecognizedItem("SKU-DEMO-001", 2, 0.9f)),
                0.9f,
                false,
                "yolov8-delta",
                List.of("bottle")
        );
        var result = helper.reconcileWithGravity(
                "[{\"skuId\":\"SKU-DEMO-001\",\"delta\":-1}]",
                vision
        );
        assertTrue(result.needReview());
        assertTrue(result.modelVersion().contains("gravity-mismatch"));
    }

    @Test
    void reconcile_fallsBackToGravityWhenVisionEmpty() {
        var vision = new VisionServiceClient.RecognitionResult(
                "T-3",
                List.of(),
                0f,
                true,
                "yolov8-delta",
                List.of()
        );
        var result = helper.reconcileWithGravity(
                "[{\"skuId\":\"SKU-DEMO-001\",\"delta\":-1}]",
                vision
        );
        assertEquals(1, result.items().size());
        assertEquals("SKU-DEMO-001", result.items().get(0).skuId());
        assertFalse(result.needReview());
        assertTrue(result.modelVersion().contains("+gravity"));
    }
}
