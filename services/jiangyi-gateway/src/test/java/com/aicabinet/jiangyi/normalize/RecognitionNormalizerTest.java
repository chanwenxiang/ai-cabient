package com.aicabinet.jiangyi.normalize;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.MappingView;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 识别归一化单测（CB-022，方案 §12：归一化 + fail-closed 语义）。
 * <p>核心契约：任一 classId 未命中映射 → 整单 empty（部分转发会算错账，禁止）；
 * modelVersion 超长截断至 64（平台落库硬约束）。</p>
 */
class RecognitionNormalizerTest {

    private final TradeInternalClient trade = Mockito.mock(TradeInternalClient.class);
    private final RecognitionNormalizer normalizer = new RecognitionNormalizer(trade);

    @Test
    void allHitMapsToItemsWithUnitConfidence() {
        when(trade.resolveMapping("D1", 7)).thenReturn(Optional.of(new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));
        when(trade.resolveMapping("D1", 9)).thenReturn(Optional.of(new MappingView(9, "雪碧", "SKU-9", "ACTIVE", "MANUAL")));

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1", List.of(
                new RecognitionNormalizer.Form(7, 2, null),
                new RecognitionNormalizer.Form(9, 1, "special-x")));

        assertEquals(2, items.size());
        assertEquals("SKU-7", items.get(0).skuId());
        assertEquals(2, items.get(0).quantity());
        assertEquals(1.0d, items.get(0).confidence());
        assertEquals("SKU-9", items.get(1).skuId());
    }

    @Test
    void anyMissFailsClosedWholeOrder() {
        when(trade.resolveMapping("D1", 7)).thenReturn(Optional.of(new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));
        // 第二行未命中：整单 empty，不允许部分转发（铁律 #16：同一能力只补一半）
        when(trade.resolveMapping("D1", 99)).thenReturn(Optional.empty());

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1", List.of(
                new RecognitionNormalizer.Form(7, 1, null),
                new RecognitionNormalizer.Form(99, 1, null)));

        assertTrue(items.isEmpty(), "任一未命中必须整单 fail-closed");
    }

    @Test
    void disabledMappingCountsAsMiss() {
        when(trade.resolveMapping(eq("D1"), anyInt())).thenReturn(
                Optional.of(new MappingView(5, "待确认", "SKU-5", "DISABLED", "MODEL_SYNC")));
        // resolveActive 语义由 trade 侧保证返回 empty；本侧对 DISABLED 行同样拒收
        when(trade.resolveMapping("D1", 5)).thenReturn(Optional.empty());
        assertTrue(normalizer.normalize("D1", List.of(new RecognitionNormalizer.Form(5, 1, null))).isEmpty());
    }

    @Test
    void invalidQuantityFailsClosed() {
        assertTrue(normalizer.normalize("D1", List.of(new RecognitionNormalizer.Form(7, 0, null))).isEmpty());
        assertTrue(normalizer.normalize("D1", List.of(new RecognitionNormalizer.Form(null, 1, null))).isEmpty());
        assertTrue(normalizer.normalize("D1", null).isEmpty());
        assertTrue(normalizer.normalize("D1", List.of()).isEmpty());
    }

    @Test
    void modelVersionTruncatedTo64() {
        String longName = "x".repeat(200);
        String version = RecognitionNormalizer.modelVersion(longName);
        assertEquals(VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH, version.length());
        assertTrue(version.startsWith("jiangyi:"));
    }

    @Test
    void modelVersionNullSafe() {
        assertEquals("jiangyi:unknown", RecognitionNormalizer.modelVersion(null));
    }

    @Test
    void nullDeviceIdShortCircuits() {
        assertTrue(normalizer.normalize(null, List.of(new RecognitionNormalizer.Form(7, 1, null))).isEmpty());
        // deviceId 为空不得触达 trade（其他用例的 stub 不算，这里精确断言 resolveMapping 零调用）
        Mockito.verify(trade, Mockito.never()).resolveMapping(Mockito.isNull(), Mockito.anyInt());
    }
}
