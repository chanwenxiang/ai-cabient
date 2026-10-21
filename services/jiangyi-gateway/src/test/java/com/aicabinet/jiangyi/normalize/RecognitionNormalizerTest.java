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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 识别归一化单测（CB-022，方案 §12：归一化 + fail-closed 语义）。
 * <p>核心契约：任一行解析不到 SKU → 整单 empty（部分转发会算错账，禁止）；
 * {@code thirdPid}（将邑标准商品 id）优先走挂接关系，落空才回落 classId 映射；
 * modelVersion 超长截断至 64（平台落库硬约束）。</p>
 * <p>金额口径由 trade 侧决定（A 档：按我方现价），本类只负责「谁」不负责「多少钱」，
 * 故上报里的 {@code price} 在此不参与任何计算——用例 {@code vendorPriceNeverAffectsItems} 钉住。</p>
 */
class RecognitionNormalizerTest {

    private final TradeInternalClient trade = Mockito.mock(TradeInternalClient.class);
    private final RecognitionNormalizer normalizer = new RecognitionNormalizer(trade);

    /** §4.2.10 稀疏形态（只有 classId/quantity，其余缺席）。 */
    private static RecognitionNormalizer.Form form(Integer classId, Integer quantity) {
        return new RecognitionNormalizer.Form(classId, quantity, null, null, null, null,
                null, null, null, null);
    }

    /** §5.2.9 完整形态。 */
    private static RecognitionNormalizer.Form fullForm(Integer classId, Integer quantity,
                                                       Integer thirdPid, Integer priceFen,
                                                       String productName) {
        return new RecognitionNormalizer.Form(classId, quantity, thirdPid, priceFen, productName,
                null, null, "可乐300ml", null, null);
    }

    @Test
    void allHitMapsToItemsWithUnitConfidence() {
        when(trade.resolveMapping("D1", 7)).thenReturn(Optional.of(new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));
        when(trade.resolveMapping("D1", 9)).thenReturn(Optional.of(new MappingView(9, "雪碧", "SKU-9", "ACTIVE", "MANUAL")));

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1", List.of(
                form(7, 2),
                fullForm(9, 1, null, null, "special-x")));

        assertEquals(2, items.size());
        assertEquals("SKU-7", items.get(0).skuId());
        assertEquals(2, items.get(0).quantity());
        assertEquals(1.0d, items.get(0).confidence());
        assertEquals("SKU-9", items.get(1).skuId());
    }

    @Test
    void thirdPidResolvesByLinkWithoutTouchingClassMapping() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.of("SKU-1018"));

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1",
                List.of(fullForm(7, 2, 1018, 300, "可口可乐 300ml")));

        assertEquals(1, items.size());
        assertEquals("SKU-1018", items.get(0).skuId());
        assertEquals(2, items.get(0).quantity());
        verify(trade, never()).resolveMapping(anyString(), anyInt());
    }

    @Test
    void thirdPidMissFallsBackToClassMapping() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.empty());
        when(trade.resolveMapping("D1", 7)).thenReturn(Optional.of(new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1",
                List.of(fullForm(7, 1, 1018, 300, "可口可乐 300ml")));

        assertEquals(List.of("SKU-7"), items.stream().map(VisionRecognitionResultDto.Item::skuId).toList());
    }

    @Test
    void zeroThirdPidIsTreatedAsAbsent() {
        // 固件补货表没有这一行时 thirdPid 回 0（int 默认值），不能拿去查挂接
        when(trade.resolveMapping("D1", 7)).thenReturn(Optional.of(new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));

        assertTrue(normalizer.normalize("D1", List.of(fullForm(7, 1, 0, 0, null))).size() == 1);
        verify(trade, never()).resolveSkuByProduct("0");
    }

    @Test
    void vendorPriceNeverAffectsItems() {
        // A 档：上报 price 只存档，归一化结果里没有金额可言——行仍是 skuId/quantity/confidence
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.of("SKU-1018"));

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1",
                List.of(fullForm(7, 3, 1018, 8000, "可口可乐")));

        assertEquals(1, items.size());
        assertEquals(3, items.get(0).quantity());
        verify(trade, never()).resolveMapping(anyString(), anyInt());
    }

    @Test
    void anyMissFailsClosedWholeOrder() {
        when(trade.resolveMapping("D1", 7)).thenReturn(Optional.of(new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));
        // 第二行未命中：整单 empty，不允许部分转发（铁律 #16：同一能力只补一半）
        when(trade.resolveMapping("D1", 99)).thenReturn(Optional.empty());

        List<VisionRecognitionResultDto.Item> items = normalizer.normalize("D1", List.of(
                form(7, 1),
                form(99, 1)));

        assertTrue(items.isEmpty(), "任一未命中必须整单 fail-closed");
    }

    @Test
    void thirdPidHitStillFailsClosedWhenOtherLineMisses() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.of("SKU-1018"));
        when(trade.resolveMapping("D1", 99)).thenReturn(Optional.empty());

        assertTrue(normalizer.normalize("D1", List.of(
                fullForm(7, 1, 1018, 300, "可乐"),
                form(99, 1))).isEmpty());
    }

    @Test
    void disabledMappingCountsAsMiss() {
        when(trade.resolveMapping(eq("D1"), anyInt())).thenReturn(
                Optional.of(new MappingView(5, "待确认", "SKU-5", "DISABLED", "MODEL_SYNC")));
        // resolveActive 语义由 trade 侧保证返回 empty；本侧对 DISABLED 行同样拒收
        when(trade.resolveMapping("D1", 5)).thenReturn(Optional.empty());
        assertTrue(normalizer.normalize("D1", List.of(form(5, 1))).isEmpty());
    }

    @Test
    void invalidQuantityFailsClosed() {
        assertTrue(normalizer.normalize("D1", List.of(form(7, 0))).isEmpty());
        assertTrue(normalizer.normalize("D1", List.of(form(null, 1))).isEmpty());
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
        assertTrue(normalizer.normalize(null, List.of(form(7, 1))).isEmpty());
        // deviceId 为空不得触达 trade（其他用例的 stub 不算，这里精确断言 resolveMapping 零调用）
        verify(trade, never()).resolveMapping(Mockito.isNull(), anyInt());
    }
}
