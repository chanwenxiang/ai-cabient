package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.BarcodeLookupDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BarcodeLookupClient 解析与校验单测（CB-025）。
 *
 * <p>只测纯逻辑（envelope 解析 / 条码形态），不发真实 HTTP——上游聚合接口
 * 匿名额度有限（20 次/日），任何自动化测试都不得消耗真实配额。</p>
 */
class BarcodeLookupClientTest {

    private final BarcodeLookupClient client = new BarcodeLookupClient(
            "https://example.invalid/api/barcode?code={code}", "test-key");

    // ---------- isValidBarcode ----------

    @Test
    void barcodeFormat_acceptsEan8UpcEan13Itf14() {
        assertThat(BarcodeLookupClient.isValidBarcode("69012345")).isTrue();      // EAN-8
        assertThat(BarcodeLookupClient.isValidBarcode("012345678905")).isTrue();  // UPC-A
        assertThat(BarcodeLookupClient.isValidBarcode("6921168509256")).isTrue(); // EAN-13
        assertThat(BarcodeLookupClient.isValidBarcode("16921168509256")).isTrue();// ITF-14
    }

    @Test
    void barcodeFormat_rejectsNonDigitWrongLengthAndNull() {
        assertThat(BarcodeLookupClient.isValidBarcode(null)).isFalse();
        assertThat(BarcodeLookupClient.isValidBarcode("")).isFalse();
        assertThat(BarcodeLookupClient.isValidBarcode("1234567")).isFalse();      // 7 位
        assertThat(BarcodeLookupClient.isValidBarcode("12345678901234567")).isFalse(); // 17 位
        assertThat(BarcodeLookupClient.isValidBarcode("692116850925a")).isFalse();// 含字母
        assertThat(BarcodeLookupClient.isValidBarcode("692116850925 ")).isFalse();// 含空格
    }

    // ---------- parse：命中 ----------

    @Test
    void parse_foundMapsCoreFieldsAndPrefersSpecificationOverNetContent() {
        String body = """
                {"code":0,"msg":"成功","data":{
                  "barcode":"6921168509256","found":true,"registered":true,
                  "name":"农夫山泉饮用天然水","brand":"农夫山泉",
                  "general_name":"水（瓶装、桶装等）",
                  "category":"水（瓶装、桶装等）(10000232)",
                  "specification":"550毫升","net_content":"550毫升",
                  "manufacturer":"农夫山泉股份有限公司",
                  "registration_message":"该商品条码已在中国物品编码中心注册，编码信息已按规定通报。",
                  "images":["https://img.example/1.jpg","https://img.example/2.jpg"]}}
                """;
        BarcodeLookupDto dto = client.parse("6921168509256", body);
        assertThat(dto.found()).isTrue();
        assertThat(dto.name()).isEqualTo("农夫山泉饮用天然水");
        assertThat(dto.brand()).isEqualTo("农夫山泉");
        assertThat(dto.spec()).isEqualTo("550毫升");
        assertThat(dto.manufacturer()).isEqualTo("农夫山泉股份有限公司");
        assertThat(dto.imageUrl()).isEqualTo("https://img.example/1.jpg");
        assertThat(dto.message()).contains("已在中国物品编码中心注册");
    }

    @Test
    void parse_foundWithoutSpecificationFallsBackToNetContent() {
        String body = """
                {"code":0,"msg":"成功","data":{"found":true,"name":"某商品","net_content":"250g"}}
                """;
        BarcodeLookupDto dto = client.parse("1", body);
        assertThat(dto.found()).isTrue();
        assertThat(dto.spec()).isEqualTo("250g");
        assertThat(dto.imageUrl()).isNull();
    }

    @Test
    void parse_freeEditionMapsSpecAndSupplierFields() {
        // 免费版 /api/barcode-lookup 字段：spec（非 specification）、supplier（非 manufacturer）、无 images
        String body = """
                {"code":0,"msg":"成功","data":{
                  "name":"农夫山泉 饮用天然水550ml","brand":"农夫山泉",
                  "manufacturer":"农夫山泉股份有限公司","spec":"550ml",
                  "price":1.5,"barcode":"6921168509256","found":true}}
                """;
        BarcodeLookupDto dto = client.parse("6921168509256", body);
        assertThat(dto.found()).isTrue();
        assertThat(dto.name()).isEqualTo("农夫山泉 饮用天然水550ml");
        assertThat(dto.brand()).isEqualTo("农夫山泉");
        assertThat(dto.spec()).isEqualTo("550ml");
        assertThat(dto.manufacturer()).isEqualTo("农夫山泉股份有限公司");
        assertThat(dto.imageUrl()).isNull();
    }

    // ---------- parse：未命中 / 上游报错 / 坏响应 ----------

    @Test
    void parse_notFoundReturnsFoundFalseWithHint() {
        String body = """
                {"code":0,"msg":"成功","data":{"barcode":"1","found":false,"name":null}}
                """;
        BarcodeLookupDto dto = client.parse("1", body);
        assertThat(dto.found()).isFalse();
        assertThat(dto.name()).isNull();
        assertThat(dto.message()).contains("未登记");
    }

    @Test
    void parse_dailyLimitExhaustedThrows429WithActionableHint() {
        // 2026-10-10 真实响应：免费版 gs1 会员接口匿名日额度仅 2 次，耗尽回 envelope code=4030
        String body = """
                {"code":4030,"msg":"未携带 API Key，匿名试用次数已用完。请在请求里带上自己的 Key；会员每日额度只统计带 Key 的调用。",
                 "data":{"limit_daily":2,"used":4,"need_key":1}}
                """;
        assertThatThrownBy(() -> client.parse("6921168509256", body))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(429))
                .hasMessageContaining("额度已用完")
                .hasMessageContaining("BARCODE_LOOKUP_API_KEY");
    }

    @Test
    void parse_qpsLimitThrows429WithRetryHint() {
        String body = """
                {"code":4029,"msg":"请求频率超出限制，当前每秒最多 1 次，请稍后再试。",
                 "data":{"limit_qps":1,"retry_after":1}}
                """;
        assertThatThrownBy(() -> client.parse("6921168509256", body))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(429))
                .hasMessageContaining("稍候 1 秒");
    }

    @Test
    void parse_upstreamErrorEnvelopeThrows502() {
        String body = "{\"code\":10001,\"msg\":\"错误的请求KEY\"}";
        assertThatThrownBy(() -> client.parse("6921168509256", body))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502))
                .hasMessageContaining("错误的请求KEY");
    }

    @Test
    void parse_malformedBodyThrows502() {
        assertThatThrownBy(() -> client.parse("6921168509256", "not-json"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void parse_nullBodyThrows502() {
        assertThatThrownBy(() -> client.parse("6921168509256", null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
