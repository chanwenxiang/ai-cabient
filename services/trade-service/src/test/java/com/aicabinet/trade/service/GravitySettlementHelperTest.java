package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重力**取证面**（DC-27 批2 之后本类的全部职责）：报文归一与净扣减换算，
 * 供补货盘点（{@code RestockSnapshotService}）、实时购物车与开门事件落库使用。
 *
 * <p>识别结算不再读取这些信号——{@code reconcileWithGravity}/{@code mergeWithVision}
 * 已随重力融合链退役删除，末了那条反射用例钉的就是「不可再加回来」。
 */
class GravitySettlementHelperTest {

    private GravitySettlementHelper helper;

    @BeforeEach
    void setUp() {
        helper = new GravitySettlementHelper(new ObjectMapper());
    }

    @Test
    void parse_blankAndMalformedYieldEmptyList() {
        assertTrue(helper.parse(null).isEmpty());
        assertTrue(helper.parse("  ").isEmpty());
        // 脏报文不得抛异常：取证面写库前调用，坏 JSON 只能降级成「无信号」
        assertTrue(helper.parse("{not-json").isEmpty());
        assertEquals(1, helper.parse("[{\"skuId\":\"SKU-A\",\"delta\":-1,\"slotId\":null}]").size());
    }

    @Test
    void toRecognizedItems_keepsOnlyNetTakeaways() {
        var items = helper.toRecognizedItems(
                "[{\"skuId\":\"SKU-A\",\"delta\":-2},{\"skuId\":\"SKU-B\",\"delta\":1},{\"skuId\":\"SKU-C\",\"delta\":0}]");
        assertEquals(1, items.size());
        assertEquals("SKU-A", items.get(0).skuId());
        // delta 是净变化（取货为负），换算成销量必须取反
        assertEquals(2, items.get(0).quantity());
    }

    @Test
    void mergeGravityJson_sumsBySlotAndSkuKey() {
        String merged = helper.mergeGravityJson(
                "[{\"skuId\":\"SKU-A\",\"delta\":-1,\"slotId\":\"a1\"}]",
                "[{\"skuId\":\"SKU-A\",\"delta\":-2,\"slotId\":\"A1\"},"
                        + "{\"skuId\":\"SKU-B\",\"delta\":-1,\"slotId\":null}]");
        List<GravityDeltaRequest.GravityDeltaItem> deltas = helper.parse(merged);
        // 同槽同 SKU 累加（slotId 大小写归一），不同槽位各自成行
        assertEquals(2, deltas.size());
        assertEquals(-3, deltas.stream().filter(d -> "SKU-A".equals(d.skuId())).findFirst().orElseThrow().delta());
    }

    @Test
    void hasSlotSpecificDeltas_requiresNonBlankSlot() {
        assertFalse(helper.hasSlotSpecificDeltas(
                List.of(new GravityDeltaRequest.GravityDeltaItem("SKU-A", -1, "  "))));
        assertTrue(helper.hasSlotSpecificDeltas(
                List.of(new GravityDeltaRequest.GravityDeltaItem("SKU-A", -1, "A1"))));
    }

    /** 负向铁律：重力信号不得回流成结算依据（曾经的造绿通路）。 */
    @Test
    void settlementFusionApisStaysRemoved() {
        for (var method : GravitySettlementHelper.class.getDeclaredMethods()) {
            String name = method.getName();
            assertFalse(name.contains("reconcile") || name.contains("mergeWithVision"),
                    "重力融合入口不得回归：" + name);
        }
    }
}
