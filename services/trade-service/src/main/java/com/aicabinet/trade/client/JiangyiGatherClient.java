package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherCheckItem;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiCategory;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.TrainedProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将邑商品采集 API 客户端（CB-023，采集文档 v1.13.0）。
 *
 * <p>与 {@link JiangyiMerchantClient} 同域同凭据（{@code lingshouapi.hunanjysmart.com} +
 * login2 mobile/password，PDF §3.2/§3.4.1 逐页核对）——独立成类只因方法域不同
 * （商品库/采集/学习/模型 15+ 方法 vs 入驻 3 方法），envelope/重登逻辑刻意保持同构。</p>
 *
 * <p>响应约定（PDF §3.5 原文）：status==200 视为正常，其他值错误信息在 data 中——
 * 统一抛 {@link IllegalStateException} 携带 msg。industrialControlModel 存将邑原值
 * （"76"/"88"）不解释语义：PDF §4.4.4.5 示例中 rk3588/rk3576 两种主板的该字段同为
 * "88"，映射关系文档自证不了，下发校验用字符串相等（CB-023 台账结论 4）。</p>
 *
 * <p>已知文档不一致：开始学习（§4.4.3）参数表仅 finishNotifyUrl/finishNotifyId 两行，
 * 请求示例却含 modelName——按示例带 modelName；**学习范围（哪些商品进模型）文档未说明，
 * 以将邑侧「已采集待学习商品集合」为准，待真机联调实锤**。</p>
 */
@Component
public class JiangyiGatherClient {

    private static final Logger log = LoggerFactory.getLogger(JiangyiGatherClient.class);

    private static final int STATUS_OK = 200;
    private static final long TOKEN_TTL_MS = 25 * 60_000L;
    /** 商品适用机型（PDF §4.1.5.2 固定值 21）；env 可覆盖防真机值漂移。 */
    private final int applyMachineId;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String merchantMobile;
    private final String merchantPassword;

    private volatile String cachedToken;
    private volatile long tokenFetchedAtMs;

    public JiangyiGatherClient(@Value("${JIANGYI_API_BASE_URL:https://lingshouapi.hunanjysmart.com}") String baseUrl,
                               @Value("${JIANGYI_MERCHANT_MOBILE:}") String merchantMobile,
                               @Value("${JIANGYI_MERCHANT_PASSWORD:}") String merchantPassword,
                               @Value("${JIANGYI_APPLY_MACHINE_ID:21}") int applyMachineId) {
        this.restClient = InternalRestClientFactory.create(baseUrl);
        this.merchantMobile = merchantMobile;
        this.merchantPassword = merchantPassword;
        this.applyMachineId = applyMachineId;
    }

    // ---------- 商品库（4.1.x） ----------

    /** 查询将邑商品库（§4.1.2 /merchant/stdSku/getList；name/barCode 均可空模糊查询）。 */
    public List<JiangyiStdSku> stdSkuList(String name, String barCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) {
            body.put("name", name);
        }
        if (barCode != null && !barCode.isBlank()) {
            body.put("barCode", barCode);
        }
        body.put("applyMachineId", applyMachineId);
        JsonNode data = post("/merchant/stdSku/getList", body, "stdSkuList");
        List<JiangyiStdSku> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                out.add(new JiangyiStdSku(
                        n.path("id").asLong(),
                        text(n, "name"),
                        text(n, "barCode"),
                        text(n, "specs"),
                        text(n, "brandName"),
                        text(n, "category"),
                        text(n, "mainImg"),
                        text(n, "stdSkuCode")));
            }
        }
        return out;
    }

    /** 获取商品分类（§4.1.4 /merchant/productCategory/getTopList，无参数）。 */
    public List<JiangyiCategory> productCategories() {
        JsonNode data = post("/merchant/productCategory/getTopList", Map.of(), "productCategories");
        List<JiangyiCategory> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                out.add(new JiangyiCategory(n.path("id").asLong(), text(n, "name")));
            }
        }
        return out;
    }

    /**
     * 新增商品（§4.1.5 /merchant/product/create）→ data=商品id。
     * color 枚举 0-10（PDF §4.1.5.2）、category ∈ bottle|box|bag|bowl|egg、
     * barCode/stdSkuCode 可选——字段校验在上层 Service（UI 表单必填兜底）。
     */
    public long productCreate(Map<String, Object> req) {
        JsonNode data = post("/merchant/product/create", req, "productCreate");
        if (data == null || data.isNull() || !data.isValueNode()) {
            throw new IllegalStateException("将邑 productCreate 响应缺少商品id");
        }
        return data.asLong();
    }

    // ---------- 采集（4.2.x） ----------

    /** 查询待采集商品列表（§4.2.2 waitGatherProduct；barCode/name 至少一项）。 */
    public JsonNode waitGatherProduct(String barCode, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("barCode", barCode == null ? "" : barCode);
        body.put("name", name == null ? "" : name);
        return post("/merchant/selfhelpGatherProduct/waitGatherProduct", body, "waitGatherProduct");
    }

    /** 采集商品开门（§4.2.3 gatherOpenDoor；identifier=CQYB 编号，doorPosition 双门柜 L/R 可空）。 */
    public void gatherOpenDoor(String identifier, String doorPosition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identifier", identifier);
        body.put("doorPosition", doorPosition == null ? "" : doorPosition);
        post("/merchant/selfhelpGatherProduct/gatherOpenDoor", body, "gatherOpenDoor");
    }

    /** 查询开门状态（§4.2.4 doorStatus）。 */
    public JsonNode doorStatus(String identifier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identifier", identifier);
        return post("/merchant/selfhelpGatherProduct/doorStatus", body, "doorStatus");
    }

    // ---------- 学习与模型（4.4.x） ----------

    /** 查询已学习准备成功商品（§4.4.2 getPageData，gatherStatus=training）→ 行含 textName。 */
    public List<TrainedProduct> trainedProducts() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gatherStatus", "training");
        body.put("page", 1);
        body.put("pageSize", 200);
        JsonNode data = post("/merchant/product/getPageData", body, "trainedProducts");
        List<TrainedProduct> out = new ArrayList<>();
        JsonNode rows = data.path("pageData");
        if (rows.isArray()) {
            for (JsonNode n : rows) {
                out.add(new TrainedProduct(
                        n.path("id").asLong(),
                        text(n, "name"),
                        text(n, "textName"),
                        text(n, "barCode")));
            }
        }
        return out;
    }

    /**
     * 开始学习（§4.4.3 commitTrainingTenantOne）。finishNotifyUrl 不得携带 token（文档明文）；
     * finishNotifyId 为我方一次性凭据（jiangyi_training_ticket）。
     */
    public void commitTraining(String modelName, String finishNotifyUrl, String finishNotifyId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelName", modelName);
        body.put("finishNotifyUrl", finishNotifyUrl);
        body.put("finishNotifyId", finishNotifyId);
        post("/merchant/selfhelpGatherProduct/commitTrainingTenantOne", body, "commitTraining");
    }

    /** 学习中商品列表（§4.4.5 studyingProduct）。 */
    public JsonNode trainingProducts() {
        return post("/merchant/selfhelpGatherProduct/studyingProduct", Map.of(), "trainingProducts");
    }

    /**
     * 查询模型（§4.4.4 getModelFileByApi，无参数）→ 列表含
     * id/modelName/modelUrl/industrialControlModel(原值 "76"/"88")/modelTextUrl/quantity。
     */
    public List<JiangyiModelFile> modelFiles() {
        JsonNode data = post("/merchant/modelFile/getModelFileByApi", Map.of(), "modelFiles");
        List<JiangyiModelFile> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                out.add(new JiangyiModelFile(
                        n.path("id").asLong(),
                        text(n, "modelName"),
                        text(n, "modelUrl"),
                        text(n, "industrialControlModel"),
                        text(n, "modelTextUrl"),
                        n.path("quantity").asInt(0)));
            }
        }
        return out;
    }

    /** 查看采集审核列表（§4.4.6 gatherCheck；productIds 可空=全部）。 */
    public List<GatherCheckItem> gatherCheck(List<Long> productIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productIds", productIds == null ? List.of() : productIds);
        JsonNode data = post("/merchant/selfhelpGatherProduct/gatherCheck", body, "gatherCheck");
        List<GatherCheckItem> out = new ArrayList<>();
        JsonNode rows = data.path("pageData");
        if (!rows.isArray() && data.isArray()) {
            rows = data;
        }
        if (rows.isArray()) {
            for (JsonNode n : rows) {
                out.add(new GatherCheckItem(
                        n.path("id").asLong(),
                        n.path("productId").asLong(),
                        text(n, "status"),
                        text(n, "rejectCause"),
                        text(n, "name"),
                        text(n, "picUrl")));
            }
        }
        return out;
    }

    // ---------- envelope / token（与 JiangyiMerchantClient 同构） ----------

    private synchronized String bearerToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now - tokenFetchedAtMs < TOKEN_TTL_MS) {
            return cachedToken;
        }
        if (merchantMobile == null || merchantMobile.isBlank()
                || merchantPassword == null || merchantPassword.isBlank()) {
            throw new IllegalStateException("将邑采集 API 凭据未配置（JIANGYI_MERCHANT_MOBILE/JIANGYI_MERCHANT_PASSWORD）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", merchantMobile);
        body.put("password", merchantPassword);
        JsonNode data = post("/merchant/authentication/login2", body, "login2");
        String token = data.isNull() || !data.isTextual() ? null : data.asText();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("将邑 login2 响应缺少 token");
        }
        cachedToken = token;
        tokenFetchedAtMs = now;
        return token;
    }

    private void invalidateToken() {
        cachedToken = null;
        tokenFetchedAtMs = 0;
    }

    private JsonNode post(String path, Map<String, Object> body, String op) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode resp = restClient.post()
                        .uri(path)
                        .header("Authorization", "Bearer " + bearerToken())
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                return parseEnvelope(resp, op);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                boolean authExpired = attempt == 0 && (code.value() == 401 || code.value() == 403);
                if (authExpired) {
                    log.warn("jiangyi gather {} got {} — token refreshed, retrying once", op, code.value());
                    invalidateToken();
                    continue;
                }
                throw new IllegalStateException("将邑采集 " + op + " HTTP " + code.value(), e);
            }
        }
        throw new IllegalStateException("将邑采集 " + op + " 重试后仍未成功");
    }

    private JsonNode parseEnvelope(JsonNode root, String op) {
        if (root == null || root.isMissingNode()) {
            throw new IllegalStateException("将邑采集 " + op + " 返回空响应");
        }
        int status = root.path("status").asInt(-1);
        String msg = root.path("msg").asText("");
        if (status != STATUS_OK) {
            throw new IllegalStateException("将邑采集 " + op + " 失败 status=" + status + " msg=" + msg);
        }
        return root.path("data");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
