package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureProduct;
import com.aicabinet.trade.dto.JiangyiRestockDtos.CabinetCaptureSession;
import com.aicabinet.trade.dto.JiangyiRestockDtos.PhoneCapturePolicy;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductCategory;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductCreateCommand;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductState;
import com.aicabinet.trade.dto.JiangyiRestockDtos.State;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockLine;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockProgress;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitCommand;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitResult;
import com.aicabinet.trade.util.YuanFen;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将邑 V16（2026-10-24）第七章「新商品录入、采集与补货对接」客户端（§7.5~§7.19，共 15 条）。
 *
 * <p>与 {@link JiangyiMerchantClient}/{@link JiangyiGatherClient} 同域同账号
 * （{@code lingshouapi.hunanjysmart.com} + §5.4.1 login2），独立成类是因为路径前缀
 * （§7.4 {@code /merchant/openapi/v2/restock/}）与错误语义不同：本族非 200 的 msg
 * 是<b>可直接展示给用户的原因</b>，故抛 {@link JiangyiRestockException} 保留原文。</p>
 *
 * <p>金额：§7.6/§7.11 的 {@code salePrice}/{@code purchasePrice} 单位是<b>元</b>，
 * 入参 DTO 一律用分，在本类出口经 {@link YuanFen} 单源换算（§3.3 总则）。</p>
 *
 * <p>已知文档缺口（按原文实现，不猜）：§7.13 只写「data 无，固定格式」没有字段表，
 * 因此 {@link #shelf(String)} 返回原始 data 节点。</p>
 */
@Component
public class JiangyiRestockClient {

    private static final Logger log = LoggerFactory.getLogger(JiangyiRestockClient.class);

    /** §7.4 接口地址前缀。 */
    static final String PREFIX = "/merchant/openapi/v2/restock";

    private static final int STATUS_OK = 200;
    private static final String AUTH_HEADER = "Authorization";
    /** §7.8 单张不超过 10MB，须为有效 jpg/png。 */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private final RestClient restClient;
    private final JiangyiMerchantTokenProvider tokenProvider;

    public JiangyiRestockClient(
            JiangyiMerchantTokenProvider tokenProvider,
            @Value("${JIANGYI_API_BASE_URL:https://lingshouapi.hunanjysmart.com}") String baseUrl) {
        this.tokenProvider = tokenProvider;
        this.restClient = InternalRestClientFactory.create(baseUrl);
    }

    // ---------- 商品（§7.5/§7.6/§7.10/§7.19） ----------

    /**
     * §7.5 按条码查询商品状态。
     *
     * <p>🔴 文档写明「首次查询已建档但未完成采集的商品时，会为其建立采集任务」——
     * 本方法有厂商侧写副作用，<b>禁止用于轮询</b>；轮询请用 {@link #batchStatus(List)}（§7.10）。</p>
     */
    public ProductState lookup(String barCode) {
        requireText(barCode, "barCode");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("barCode", barCode.trim());
        return toProductState(post("/product/lookup", body, "productLookup"));
    }

    /** §7.6 新建商品；同一条码重复调用不会重复建档，直接返回当前状态。 */
    public ProductState create(ProductCreateCommand cmd) {
        requireText(cmd.barCode(), "barCode");
        if (cmd.salePriceCents() <= 0) {
            throw new IllegalArgumentException("salePrice 必须大于 0（§7.6）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("barCode", cmd.barCode().trim());
        body.put("salePrice", YuanFen.toYuan(cmd.salePriceCents()));
        if (cmd.purchasePriceCents() != null) {
            body.put("purchasePrice", YuanFen.toYuan(cmd.purchasePriceCents()));
        }
        putIfText(body, "name", cmd.name());
        putIfText(body, "brandName", cmd.brandName());
        putIfText(body, "specs", cmd.specs());
        putIfText(body, "category", cmd.category());
        putIfPresent(body, "categoryId", cmd.categoryId());
        putIfPresent(body, "colorId", cmd.colorId());
        putIfText(body, "mainImg", cmd.mainImg());
        return toProductState(post("/product/create", body, "productCreate"));
    }

    /** §7.10 批量查询商品状态（1~100 个条码；轮询用这个，不要用 7.5）。 */
    public List<ProductState> batchStatus(List<String> barCodes) {
        if (barCodes == null || barCodes.isEmpty() || barCodes.size() > 100) {
            throw new IllegalArgumentException("barCodes 数量必须为 1~100（§7.10）");
        }
        Map<String, Object> body = Map.of("barCodes", barCodes);
        JsonNode data = post("/product/status", body, "productStatus");
        List<ProductState> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                out.add(toProductState(n));
            }
        }
        return out;
    }

    /** §7.19 商户商品类目（§7.6 的 categoryId 来源）。 */
    public List<ProductCategory> categories() {
        JsonNode data = post("/product/categories", Map.of(), "productCategories");
        List<ProductCategory> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                out.add(new ProductCategory(n.path("categoryId").asInt(),
                        text(n, "name"), n.path("parentId").asInt()));
            }
        }
        return out;
    }

    // ---------- 手机采集（§7.7/§7.8/§7.9） ----------

    /** §7.7 拍摄要求；张数以返回的 {@code shotCount} 为准（§3.2 写「8 张（或 16 张）」）。 */
    public PhoneCapturePolicy phoneCapturePolicy() {
        JsonNode data = post("/phoneCapture/policy", Map.of(), "phoneCapturePolicy");
        List<String> hints = new ArrayList<>();
        JsonNode arr = data == null ? null : data.path("shotHints");
        if (arr != null && arr.isArray()) {
            arr.forEach(h -> hints.add(h.asText()));
        }
        return new PhoneCapturePolicy(data == null ? 0 : data.path("shotCount").asInt(), hints);
    }

    /**
     * §7.8 上传图片（multipart/form-data 字段名 {@code file}），返回厂商 OSS 地址。
     *
     * <p>🔴 §7.9 只接受本接口返回的地址（附录 B 明确拒绝外部图片地址），
     * 所以我方自有存储的图必须先取字节再经此上传，不能直接把我们的 URL 传给厂商。</p>
     */
    public String uploadImage(byte[] content, String filename, String contentType) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片超过 10MB（§7.8）");
        }
        String type = normalizeImageType(contentType, filename);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(type));
        return execute(() -> restClient.post().uri(PREFIX + "/image/upload")
                .header(AUTH_HEADER, "Bearer " + tokenProvider.token())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve().body(JsonNode.class), "imageUpload").path("url").asText();
    }

    /** §7.9 提交手机采集；{@code imageUrls} 必须全部来自 §7.8，顺序与 §7.7 的 shotHints 一致。 */
    public ProductState submitPhoneCapture(long productId, List<String> imageUrls) {
        if (productId <= 0) {
            throw new IllegalArgumentException("productId 非法");
        }
        if (imageUrls == null || imageUrls.isEmpty()) {
            throw new IllegalArgumentException("imageUrls 不能为空（§7.9）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("imageUrls", imageUrls);
        return toProductState(post("/phoneCapture/submit", body, "phoneCaptureSubmit"));
    }

    // ---------- 补货（§7.11/§7.12/§7.13） ----------

    /**
     * §7.11 按层提交补货（一次提交 = 该层<b>最终库存</b>；未列出的商品从该层下架）。
     *
     * <p>{@code requestId} 重试保持不变且内容一致；内容变化必须换新号（附录 B「补货内容已变更」）。
     * 单门柜不传 {@code floorOrientation}。</p>
     */
    public RestockSubmitResult submitRestock(RestockSubmitCommand cmd) {
        requireText(cmd.requestId(), "requestId");
        if (cmd.requestId().length() > 64) {
            throw new IllegalArgumentException("requestId 长度必须 1~64（§7.11）");
        }
        requireText(cmd.deviceIdentifier(), "deviceIdentifier");
        if (cmd.floorNum() < 1) {
            throw new IllegalArgumentException("floorNum 从 1 开始（§7.11）");
        }
        if (cmd.floorOrientation() != null && !cmd.floorOrientation().isBlank()
                && !List.of("left", "right").contains(cmd.floorOrientation())) {
            throw new IllegalArgumentException("floorOrientation 只能是 left/right（§7.11）");
        }
        if (cmd.products() == null || cmd.products().isEmpty()) {
            throw new IllegalArgumentException("products 不能为空（§7.11）");
        }
        List<Map<String, Object>> products = new ArrayList<>();
        for (RestockLine line : cmd.products()) {
            boolean hasProductId = line.productId() != null && line.productId() > 0;
            boolean hasBarCode = line.barCode() != null && !line.barCode().isBlank();
            if (hasProductId == hasBarCode) {
                throw new IllegalArgumentException("productId 与 barCode 必须二选一（§7.11）");
            }
            if (line.stockNum() < 0) {
                throw new IllegalArgumentException("stockNum 必须 ≥ 0（§7.11）");
            }
            if (line.salePriceCents() <= 0) {
                throw new IllegalArgumentException("salePrice 必须大于 0（§7.11）");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (hasProductId) {
                item.put("productId", line.productId());
            } else {
                item.put("barCode", line.barCode().trim());
            }
            item.put("stockNum", line.stockNum());
            item.put("salePrice", YuanFen.toYuan(line.salePriceCents()));
            products.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", cmd.requestId());
        body.put("deviceIdentifier", cmd.deviceIdentifier());
        body.put("floorNum", cmd.floorNum());
        if (cmd.floorOrientation() != null && !cmd.floorOrientation().isBlank()) {
            body.put("floorOrientation", cmd.floorOrientation());
        }
        putIfText(body, "pic", cmd.picUrl());
        body.put("products", products);

        JsonNode data = post("/restock/submit", body, "restockSubmit");
        List<CabinetCaptureProduct> pending = new ArrayList<>();
        JsonNode arr = data == null ? null : data.path("cabinetCaptureProducts");
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> pending.add(toCaptureProduct(n)));
        }
        return new RestockSubmitResult(data == null ? null : text(data, "historyNo"), pending);
    }

    /** §7.12 查询识别参数下发进度；建议每 5 秒一次，{@code ready=true} 才可正常购物（§6.3）。 */
    public RestockProgress restockProgress(String deviceIdentifier, String historyNo) {
        requireText(deviceIdentifier, "deviceIdentifier");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceIdentifier", deviceIdentifier.trim());
        putIfText(body, "historyNo", historyNo);
        JsonNode data = post("/restock/progress", body, "restockProgress");
        List<String> names = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        if (data != null) {
            data.path("pendingNames").forEach(n -> names.add(n.asText()));
            data.path("pendingCodes").forEach(n -> codes.add(n.asText()));
        }
        return new RestockProgress(data != null && data.path("ready").asBoolean(false),
                data == null ? null : text(data, "stage"),
                data == null ? 0 : data.path("total").asInt(),
                data == null ? 0 : data.path("applied").asInt(),
                names, codes);
    }

    /**
     * §7.13 查询当前货道（各层商品与库存），用于对账。
     *
     * <p>🔴 文档只写「data 无，固定格式」，未给字段表 ⇒ 原样返回 data 节点，
     * 等厂商补字段表后再建模（见 `docs/JIANGYI_INTERFACE_CHECKLIST.md` 待确认项）。</p>
     */
    public JsonNode shelf(String deviceIdentifier) {
        requireText(deviceIdentifier, "deviceIdentifier");
        return post("/restock/shelf", Map.of("deviceIdentifier", deviceIdentifier.trim()), "restockShelf");
    }

    // ---------- 柜内采集（§7.14~§7.18） ----------

    /** §7.14 查询待柜内采集商品；{@code onShelf=false} 的需先补货上架（§7.11）。 */
    public List<CabinetCaptureProduct> cabinetCaptureList(String deviceIdentifier) {
        requireText(deviceIdentifier, "deviceIdentifier");
        JsonNode data = post("/cabinetCapture/list",
                Map.of("deviceIdentifier", deviceIdentifier.trim()), "cabinetCaptureList");
        List<CabinetCaptureProduct> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(n -> out.add(toCaptureProduct(n)));
        }
        return out;
    }

    /** §7.15 开始柜内采集（厂商云自动开锁并录制；同一台柜同时只能有一个会话）。 */
    public CabinetCaptureSession startCabinetCapture(String deviceIdentifier, long productId) {
        requireText(deviceIdentifier, "deviceIdentifier");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceIdentifier", deviceIdentifier.trim());
        body.put("productId", productId);
        return toCaptureSession(post("/cabinetCapture/start", body, "cabinetCaptureStart"));
    }

    /** §7.16 查询柜内采集进度（建议每秒一次；phase 见文档 7.16 表）。 */
    public CabinetCaptureSession cabinetCaptureStatus(String deviceIdentifier, int sessionId) {
        return toCaptureSession(post("/cabinetCapture/status",
                sessionBody(deviceIdentifier, sessionId), "cabinetCaptureStatus"));
    }

    /** §7.17 结束柜内采集（关门后调用，设备开始上传柜内图）。 */
    public CabinetCaptureSession finishCabinetCapture(String deviceIdentifier, int sessionId) {
        return toCaptureSession(post("/cabinetCapture/finish",
                sessionBody(deviceIdentifier, sessionId), "cabinetCaptureFinish"));
    }

    /** §7.18 取消柜内采集（未结束会话会占用该柜约 15 分钟）。 */
    public CabinetCaptureSession cancelCabinetCapture(String deviceIdentifier, int sessionId) {
        return toCaptureSession(post("/cabinetCapture/cancel",
                sessionBody(deviceIdentifier, sessionId), "cabinetCaptureCancel"));
    }

    // ---------- HTTP / envelope ----------

    private JsonNode post(String path, Map<String, Object> body, String op) {
        return execute(() -> restClient.post().uri(PREFIX + path)
                .header(AUTH_HEADER, "Bearer " + tokenProvider.token())
                .body(body)
                .retrieve().body(JsonNode.class), op);
    }

    /**
     * 统一执行：HTTP 401/403 与 envelope.status==401 都视为 token 过期，失效后重试一次。
     */
    private JsonNode execute(java.util.function.Supplier<JsonNode> call, String op) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return parseEnvelope(call.get(), op);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                if (attempt == 0 && JiangyiMerchantTokenProvider.isAuthError(code)) {
                    log.warn("jiangyi restock {} got {} — token refreshed, retrying once", op, code.value());
                    tokenProvider.invalidate();
                    continue;
                }
                throw new IllegalStateException("将邑补货接口 " + op + " HTTP " + code.value(), e);
            } catch (JiangyiRestockException e) {
                if (attempt == 0 && e.status() == 401) {
                    log.warn("jiangyi restock {} envelope 401 — token refreshed, retrying once", op);
                    tokenProvider.invalidate();
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("将邑补货接口 " + op + " 重试后仍未成功");
    }

    private JsonNode parseEnvelope(JsonNode root, String op) {
        if (root == null || root.isMissingNode()) {
            throw new IllegalStateException("将邑补货接口 " + op + " 返回空响应");
        }
        int status = root.path("status").asInt(-1);
        String msg = root.path("msg").asText("");
        if (status != STATUS_OK) {
            throw new JiangyiRestockException(op, status, msg);
        }
        return root.path("data");
    }

    // ---------- 解析 ----------

    private static ProductState toProductState(JsonNode n) {
        long pid = n.path("productId").asLong(0);
        return new ProductState(text(n, "barCode"), State.parse(text(n, "state")),
                text(n, "nextAction"), pid > 0 ? pid : null, text(n, "name"), text(n, "rejectCause"),
                text(n, "state"));
    }

    private static CabinetCaptureProduct toCaptureProduct(JsonNode n) {
        return new CabinetCaptureProduct(n.path("productId").asLong(), text(n, "name"),
                text(n, "barCode"), text(n, "mainImg"), n.path("onShelf").asBoolean(false));
    }

    private static CabinetCaptureSession toCaptureSession(JsonNode n) {
        return new CabinetCaptureSession(n.path("sessionId").asInt(), text(n, "deviceIdentifier"),
                n.path("productId").asLong(), text(n, "phase"), text(n, "error"));
    }

    private static Map<String, Object> sessionBody(String deviceIdentifier, int sessionId) {
        requireText(deviceIdentifier, "deviceIdentifier");
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId 非法（§7.15 返回）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceIdentifier", deviceIdentifier.trim());
        body.put("sessionId", sessionId);
        return body;
    }

    // ---------- 小工具 ----------

    private static String normalizeImageType(String contentType, String filename) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("jpeg") || type.contains("jpg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (type.contains("png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        throw new IllegalArgumentException("图片必须是 jpg/png（§7.8）");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static void putIfText(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, Integer value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
