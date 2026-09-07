package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SettlementDecision 矩阵：与 SettlementService.blocksSilentSettle / reviewReasonFor 对齐。 */
class SettlementDecisionTest {

    @Test
    void highConfidence_finalize() {
        var r = recognition(false, "cabinet-skus-v1");
        assertEquals(SettlementDecision.FINALIZE, SettlementDecision.classify(r));
        assertFalse(SettlementDecision.classify(r).requiresHumanReview());
    }

    @Test
    void needReview_generic() {
        var r = recognition(true, "cabinet-skus-v1");
        assertEquals(SettlementDecision.HUMAN_REVIEW_GENERIC, SettlementDecision.classify(r));
        assertEquals("识别结果需人工审核", SettlementDecision.classify(r).reviewReason(r));
    }

    @ParameterizedTest
    @CsvSource({
            "mock-v1, HUMAN_REVIEW_MOCK, 模拟/兜底识别结果，非生产精度，需人工审核",
            "fallback-v2, HUMAN_REVIEW_MOCK, 模拟/兜底识别结果，非生产精度，需人工审核",
            "yolov8+gravity-mismatch, HUMAN_REVIEW_MISMATCH, 视觉与重力数量不一致，需人工审核",
            "gravity-fill, HUMAN_REVIEW_GRAVITY_FILL, 视觉为空，仅有重力信号（非生产识别精度），需人工审核"
    })
    void versionForcesReview(String version, SettlementDecision expected, String reason) {
        var r = recognition(false, version);
        assertTrue(SettlementService.blocksSilentSettle(r));
        assertEquals(expected, SettlementDecision.classify(r));
        assertEquals(reason, SettlementDecision.classify(r).reviewReason(r));
    }

    private static VisionServiceClient.RecognitionResult recognition(boolean needReview, String version) {
        return new VisionServiceClient.RecognitionResult(
                "T-1",
                List.of(new VisionServiceClient.RecognizedItem("SKU-A", 1, 0.9f)),
                0.9f,
                needReview,
                version,
                List.of());
    }
}
