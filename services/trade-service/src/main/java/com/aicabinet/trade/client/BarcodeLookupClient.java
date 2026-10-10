package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.BarcodeLookupDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 商品条码（69 码）官方资料查询客户端（CB-025/026，商品建档「查官方资料」）。
 *
 * <p>中国物品编码中心（GS1 China）无免费公开 API（企业 API 需认证+年费，CB-025 证据）；
 * 默认走直连编码中心注册库的聚合查询接口<b>免费版</b> {@code /api/barcode-lookup}
 * ——匿名 20 次/日、QPS 1，传 Bearer Key 提额。商品建档是低频操作，匿名额度够用；
 * 如需扩量配置 {@code BARCODE_LOOKUP_API_KEY}（2026-10-10 实测：会员版 gs1 接口匿名仅 2 次/日，
 * 匿名部署必须用免费版，故默认 URL 从 gs1 切到 free 版）。</p>
 *
 * <p>可配置项（均走环境变量，密钥禁提交铁律 #27）：
 * <ul>
 *   <li>{@code BARCODE_LOOKUP_URL}：查询 URL 模板，必须含 {@code {code}} 占位符
 *       （默认 {@code https://v1.apizero.cn/api/barcode-lookup?barcode={code}}，免费版）;</li>
 *   <li>{@code BARCODE_LOOKUP_API_KEY}：可选，作为 {@code Authorization: Bearer}（免费版）
 *       与 {@code X-API-Key}（会员版 gs1）双头透传，两种上游都兼容。</li>
 * </ul></p>
 *
 * <p>仅覆盖国内注册条码（69 前缀为主）；未登记条码返回 found=false 而非报错。
 * 响应 envelope：{@code {code:0, msg, data:{found, name, brand, spec|specification,
 * net_content, manufacturer, category, images[]?}}}（免费版无 images/registration_message，
 * 解析兼容两种字段名）。</p>
 *
 * <p>错误分层（用户看到的文案必须能指导下一步动作）：上游 429 / envelope 4030（日额度耗尽）
 * → 429 +「配置 Key 或手工填写」；envelope 4029（QPS 超限）→ 429 +「稍后再试」；
 * 其余上游错误 → 502 +「稍后重试或手工填写」。</p>
 */
@Component
public class BarcodeLookupClient {

    private static final Logger log = LoggerFactory.getLogger(BarcodeLookupClient.class);

    /** 上游额度耗尽错误码（envelope code）：4030=匿名日额度用完，4029=QPS 超限。 */
    static final int UPSTREAM_CODE_DAILY_LIMIT = 4030;
    static final int UPSTREAM_CODE_QPS_LIMIT = 4029;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String urlTemplate;
    private final String apiKey;

    public BarcodeLookupClient(
            @Value("${BARCODE_LOOKUP_URL:https://v1.apizero.cn/api/barcode-lookup?barcode={code}}")
            String urlTemplate,
            @Value("${BARCODE_LOOKUP_API_KEY:}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        // 上游本地库未命中会回源编码中心，留足读超时
        factory.setReadTimeout(8_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.urlTemplate = urlTemplate;
        this.apiKey = apiKey;
    }

    /** 查官方资料。code 须已通过 {@link #isValidBarcode} 校验。 */
    public BarcodeLookupDto lookup(String code) {
        String body;
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(urlTemplate, code);
            if (apiKey != null && !apiKey.isBlank()) {
                // 免费版鉴权 Authorization: Bearer；会员版 gs1 用 X-API-Key——双发兼容两种上游
                spec = spec.header("Authorization", "Bearer " + apiKey).header("X-API-Key", apiKey);
            }
            body = spec.retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("条码查询上游调用失败 code={} : {}", code, e.getMessage());
            // HTTP 429 = 额度/频率耗尽，文案要能指导下一步（配 Key 或手工填写），别笼统说服务不可用
            if (e.getStatusCode().value() == 429 || String.valueOf(e.getMessage()).contains("429")) {
                throw dailyOrQps(e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "条码查询服务暂不可用，请稍后重试或手工填写");
        } catch (Exception e) {
            log.warn("条码查询上游调用失败 code={} : {}", code, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "条码查询服务暂不可用，请稍后重试或手工填写");
        }
        return parse(code, body);
    }

    /** 解析上游 envelope；额度/QPS 错误单独 429，其余上游报错 502，未登记条码回 found=false。 */
    BarcodeLookupDto parse(String code, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            log.warn("条码查询响应解析失败 code={} : {}", code, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "条码查询服务响应异常");
        }
        int upstreamCode = root.path("code").asInt(-1);
        if (upstreamCode != 0) {
            if (upstreamCode == UPSTREAM_CODE_DAILY_LIMIT || upstreamCode == UPSTREAM_CODE_QPS_LIMIT) {
                throw dailyOrQps(root.path("msg").asText(""));
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "条码查询服务返回错误：" + root.path("msg").asText("未知原因"));
        }
        JsonNode data = root.path("data");
        if (!data.path("found").asBoolean(false)) {
            return BarcodeLookupDto.notFound("编码中心未登记该条码（进口商品或新商品可能未通报）");
        }
        String spec = firstNonBlank(text(data, "spec"), firstNonBlank(text(data, "specification"), text(data, "net_content")));
        return new BarcodeLookupDto(
                true,
                text(data, "name"),
                text(data, "brand"),
                spec,
                firstNonBlank(text(data, "manufacturer"), text(data, "supplier")),
                text(data, "category"),
                firstImage(data),
                text(data, "registration_message"));
    }

    /** 按上游信息区分「日额度用完」与「QPS 超限」，给出可行动的提示。 */
    private static ResponseStatusException dailyOrQps(String upstreamMsg) {
        boolean qps = upstreamMsg != null && upstreamMsg.contains("每秒");
        if (qps) {
            return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "查询过于频繁，请稍候 1 秒再试");
        }
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "今日查码额度已用完（免费额度有限），请手工填写；如需提额可配置 BARCODE_LOOKUP_API_KEY");
    }

    /** 商品条码合法形态：EAN-8 / UPC-A(12) / EAN-13 / ITF-14 纯数字。 */
    public static boolean isValidBarcode(String code) {
        return code != null && code.matches("\\d{8}|\\d{12}|\\d{13}|\\d{14}");
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() || "null".equals(v) ? null : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private static String firstImage(JsonNode data) {
        JsonNode images = data.path("images");
        if (images.isArray() && !images.isEmpty() && images.get(0).isTextual()) {
            String url = images.get(0).asText();
            return url == null || url.isBlank() ? null : url.trim();
        }
        return null;
    }
}
