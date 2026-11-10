package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiCategory;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiTextNameSku;
import com.aicabinet.trade.dto.JiangyiGatherDtos.LearnableProduct;
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
        JsonNode data = post("/merchant/stdSku/getList", body, true, "stdSkuList");
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
                        text(n, "stdSkuCode"),
                        text(n, "barCodeSource")));
            }
        }
        return out;
    }

    /**
     * 按模型 classes.txt 地址查将邑标准商品（§5.5.3 /merchant/stdSku/getStdSkuQueryByTextNames）。
     *
     * <p>2026-10-22 用厂商两份真 classes 文件实测：回包<b>稀疏</b>——69 行的文件只回 67 行，
     * 对不上标准商品库的类目直接不出现在 data 里。所以它只能用来「把 classes 行翻成人能看懂的
     * 品名+条码」，不能当全量映射源（缺的行仍须人工挂接）。</p>
     */
    public List<JiangyiTextNameSku> stdSkuByTextNames(String modelTextUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelTextUrl", modelTextUrl);
        JsonNode data = post("/merchant/stdSku/getStdSkuQueryByTextNames", body, true, "stdSkuByTextNames");
        List<JiangyiTextNameSku> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                out.add(new JiangyiTextNameSku(
                        n.hasNonNull("stdSkuId") ? n.path("stdSkuId").asLong() : null,
                        text(n, "stdSkuName"),
                        text(n, "textName"),
                        text(n, "barCode"),
                        n.hasNonNull("classId") ? n.path("classId").asInt() : null));
            }
        }
        return out;
    }

    /**
     * 删除商品（§4.1.6 /merchant/product/delete，入参只有 id，响应 data=null）。
     * 厂商侧不可逆——调用方的「只删我方新建的」闸门见
     * {@link com.aicabinet.trade.service.SkuJiangyiLinkService#deleteFromJiangyi}。
     */
    public void productDelete(long productId) {
        post("/merchant/product/delete", Map.of("id", productId), true, "productDelete");
    }

    /**
     * 改商品主图（V1.13 §4.1.7 /merchant/product/updateMainImg，响应 data=null）。
     * <p>🔴 真实路径是大写 I：文档 OCR 的 {@code updateMainlmg}（小写 L）照抄必 404，见 H-24。
     * 厂商侧只放开改我方新建（CREATED）的商品，闸门见
     * {@link com.aicabinet.trade.service.SkuJiangyiLinkService#updateMainImage}。</p>
     */
    public void updateMainImage(long productId, String mainImg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("mainImg", mainImg);
        post("/merchant/product/updateMainImg", body, true, "productUpdateMainImg");
    }

    // ---------- 学习与模型（4.4.x） ----------

    /**
     * 厂商「学习准备成功」名单（§4.4.2 getPageData，gatherStatus=training）→ 行含 textName。
     *
     * <p>🔴 <b>在列 = 采集成档 + 厂商审核通过，不等于已学成</b>：文档 §4.4.1 流程图把它排在
     * 「开始学习」之前，且 2026-10-27 实测本租户商品在列时模型数与学习中数同时为 0。
     * 判学成/可识别要用 §4.4.4 模型 + classes（§5.5.3），<b>不要顺手把这里当学成证据</b>。</p>
     */
    public List<LearnableProduct> learnableProducts() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gatherStatus", "training");
        body.put("page", 1);
        body.put("pageSize", 200);
        JsonNode data = post("/merchant/product/getPageData", body, true, "learnableProducts");
        List<LearnableProduct> out = new ArrayList<>();
        JsonNode rows = data.path("pageData");
        if (rows.isArray()) {
            for (JsonNode n : rows) {
                out.add(new LearnableProduct(
                        n.path("id").asLong(),
                        text(n, "name"),
                        text(n, "textName"),
                        text(n, "barCode")));
            }
        }
        return out;
    }

    /**
     * 查询模型（§4.4.4 getModelFileByApi，无参数）→ 列表含
     * id/modelName/customerModelName(模型初命名)/modelUrl/industrialControlModel(原值 "76"/"88")
     * /modelTextUrl/quantity。
     *
     * <p>文档 §4.4.1 流程图逐字「两种工控类型76/88，所以两个模型」⇒ <b>一次学习应回两行</b>
     * （76 与 88 各一）。只回一行是厂商侧异常，第一颗真模型出现时按这条判。</p>
     */
    public List<JiangyiModelFile> modelFiles() {
        JsonNode data = post("/merchant/modelFile/getModelFileByApi", Map.of(), true, "modelFiles");
        List<JiangyiModelFile> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode n : data) {
                out.add(new JiangyiModelFile(
                        n.path("id").asLong(),
                        text(n, "modelName"),
                        text(n, "customerModelName"),
                        text(n, "modelUrl"),
                        text(n, "industrialControlModel"),
                        text(n, "modelTextUrl"),
                        n.path("quantity").asInt(0)));
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
        JsonNode data = post("/merchant/authentication/login2", body, false, "login2");
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

    /**
     * 统一 POST：校验 envelope.status==200，返回 data 节点。
     * withAuth=true 时带 Bearer 并对 401/403 强制重登重试一次；login2 自身必须 withAuth=false，
     * 否则 bearerToken() → post() → bearerToken() 无限递归（2026-10-21 追踪号 3b6fec1ee3c1 实证）。
     */
    private JsonNode post(String path, Map<String, Object> body, boolean withAuth, String op) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                RestClient.RequestBodySpec spec = restClient.post().uri(path);
                if (withAuth) {
                    spec = spec.header("Authorization", "Bearer " + bearerToken());
                }
                JsonNode resp = spec.body(body).retrieve().body(JsonNode.class);
                return parseEnvelope(resp, op);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                boolean authExpired = withAuth && attempt == 0
                        && (code.value() == 401 || code.value() == 403);
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
