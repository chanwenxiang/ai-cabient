package com.aicabinet.trade.service;

import com.aicabinet.trade.sms.AliyunSmsSender.SmsReceipt;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短信到达率聚合口径的钉测。夹具 {@code /sms/delivery-receipts-golden.json} 是**两侧共用**的锚点：
 * 本测试（定时巡检）与 {@code scripts/sms-delivery-report.mjs --selftest}（运维即时排查）读同一份文件，
 * 谁单独改口径谁红。
 */
class SmsDeliveryStatsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> CODES_TYPE = new TypeReference<>() {};

    @Test
    void of_goldenFixture_shouldMatchEveryScenario() throws Exception {
        JsonNode scenarios = fixture().get("scenarios");
        assertTrue(scenarios.size() >= 4, "夹具场景数不足，钉测本身退化了：" + scenarios.size());
        for (JsonNode sc : scenarios) {
            String name = sc.get("name").asText();
            SmsDeliveryStats stats = SmsDeliveryStats.of(rowsOf(sc.get("rows")));
            JsonNode exp = sc.get("expected");
            assertEquals(exp.get("total").asInt(), stats.total(), name + ".total");
            assertEquals(exp.get("delivered").asInt(), stats.delivered(), name + ".delivered");
            assertEquals(exp.get("pending").asInt(), stats.pending(), name + ".pending");
            assertEquals(exp.get("failed").asInt(), stats.failed(), name + ".failed");
            assertEquals(exp.get("failPercent").asInt(), stats.failPercent(), name + ".failPercent");
            assertEquals(MAPPER.convertValue(exp.get("codes"), CODES_TYPE), stats.codes(), name + ".codes");
            var over = exp.get("overThreshold").fields();
            while (over.hasNext()) {
                Map.Entry<String, JsonNode> t = over.next();
                int threshold = Integer.parseInt(t.getKey());
                assertEquals(t.getValue().asBoolean(), stats.isFailRatioOver(threshold),
                        name + ".isFailRatioOver(" + threshold + ")");
            }
        }
    }

    /**
     * 判定不能用取整后的百分比：5/24 = 20.83% 取整成 20%，与阈值 20 比正好漏报。
     * 这条把「failPercent 只用于展示」钉成断言，防止有人顺手把判定改回 failPercent() &gt; threshold。
     */
    @Test
    void judgementMustUseExactRatio_notFloorPercent() throws Exception {
        JsonNode sc = scenario("floor_would_under_alert");
        SmsDeliveryStats stats = SmsDeliveryStats.of(rowsOf(sc.get("rows")));
        assertEquals(20, stats.failPercent(), "取整口径就是会读成 20%");
        assertTrue(stats.isFailRatioOver(20), "20.83% 必须判为超阈值 20%");
        assertFalse(stats.isFailRatioOver(21), "20.83% 不该判为超阈值 21%");
    }

    /** 空回执 ≠ 健康：不抛、计数全 0、不判超阈值（调用方须按「样本不足」处理）。 */
    @Test
    void emptyReceipts_areNotHealthyButDoNotThrow() throws Exception {
        SmsDeliveryStats stats = SmsDeliveryStats.of(List.of());
        assertEquals(0, stats.total());
        assertEquals(0, stats.failPercent());
        assertFalse(stats.isFailRatioOver(0), "零样本永远不该判超阈值");
        assertTrue(stats.summary().contains("回执 0 条"), stats.summary());
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream in = SmsDeliveryStatsTest.class.getResourceAsStream("/sms/delivery-receipts-golden.json")) {
            assertNotNull(in, "夹具不在 classpath：services/trade-service/src/test/resources/sms/delivery-receipts-golden.json");
            return MAPPER.readTree(in);
        }
    }

    private static JsonNode scenario(String name) throws Exception {
        for (JsonNode sc : fixture().get("scenarios")) {
            if (name.equals(sc.get("name").asText())) {
                return sc;
            }
        }
        throw new IllegalStateException("夹具里没有场景 " + name);
    }

    private static List<SmsReceipt> rowsOf(JsonNode rows) {
        List<SmsReceipt> out = new ArrayList<>();
        for (JsonNode r : rows) {
            out.add(new SmsReceipt(
                    text(r, "PhoneNum"),
                    text(r, "SendDate"),
                    text(r, "ReceiveDate"),
                    r.path("SendStatus").asInt(0),
                    text(r, "ErrCode"),
                    text(r, "TemplateCode")));
        }
        return out;
    }

    /** 空串与 null 都要保留原样：ErrCode="" 和 ErrCode=null 在口径里都归 UNKNOWN，但 JSON 侧要能区分写出。 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

}
