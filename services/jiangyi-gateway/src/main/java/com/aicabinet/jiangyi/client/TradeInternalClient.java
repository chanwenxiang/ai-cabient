package com.aicabinet.jiangyi.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.common.security.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * gateway → trade 内部客户端（CB-022）：设备目录查询、token 回执、映射解析、
 * 门状态/识别结果转发、识别超时触发。统一 ApiResponse(code=0) envelope。
 * <p>与 trade 侧 DeviceServiceClient 同款：InternalApiConstants.API_KEY_HEADER 鉴权，
 * 超时 10s/30s（trade 侧 InternalRestClientFactory 为包级私有，此处同参数自建）。</p>
 */
@Component
public class TradeInternalClient {

    private static final Logger log = LoggerFactory.getLogger(TradeInternalClient.class);

    private final RestClient restClient;
    private final InternalApiProperties internalApiProperties;

    public TradeInternalClient(@Value("${aicabinet.trade-service.url:http://localhost:8080}") String baseUrl,
                               InternalApiProperties internalApiProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.internalApiProperties = internalApiProperties;
    }

    /** 构造 ApiResponse&lt;T&gt; 的泛型 Type（Jackson/RestClient 泛型反序列化）。 */
    private static Type apiResponseType(Class<?> dataType) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[]{dataType};
            }

            @Override
            public Type getRawType() {
                return ApiResponse.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    // ---------- 设备面 ----------

    public Optional<JiangyiDeviceView> deviceBySn(String deviceSn) {
        ApiResponse<JiangyiDeviceView> resp = get("/internal/v1/jiangyi/devices/by-sn/{deviceSn}",
                JiangyiDeviceView.class, deviceSn);
        return Optional.ofNullable(resp == null ? null : resp.data());
    }

    public Optional<JiangyiDeviceView> device(String deviceId) {
        ApiResponse<JiangyiDeviceView> resp = get("/internal/v1/jiangyi/devices/{deviceId}",
                JiangyiDeviceView.class, deviceId);
        return Optional.ofNullable(resp == null ? null : resp.data());
    }

    /** token 签发回执（只记时间；吊销走 bumpTokenVersion，方案 §3）。 */
    public void tokenIssued(String deviceId) {
        post("/internal/v1/jiangyi/devices/{deviceId}/token-issued", null, deviceId);
    }

    /** WS 在线回执（建连 + 心跳节流上报；trade 侧据此置 device_info 在线）。 */
    public void wsOnline(String deviceId) {
        post("/internal/v1/jiangyi/devices/{deviceId}/ws-online", null, deviceId);
    }

    /** WS 断开回执（trade 侧据此置 device_info 离线，开门校验 ensureDeviceOnline 即时生效）。 */
    public void wsOffline(String deviceId) {
        post("/internal/v1/jiangyi/devices/{deviceId}/ws-offline", null, deviceId);
    }

    // ---------- 映射面 ----------

    /** ACTIVE 映射点查；empty = 未命中（调用方 fail-closed → recognizeTimeout）。 */
    public Optional<MappingView> resolveMapping(String deviceId, Integer classId) {
        ApiResponse<MappingView> resp = get("/internal/v1/jiangyi/class-mappings/{deviceId}/{classId}",
                MappingView.class, deviceId, classId);
        return Optional.ofNullable(resp == null ? null : resp.data());
    }

    /**
     * 按将邑标准商品 id（上报里的 thirdPid）解析我方 SKU——走 {@code sku_jiangyi_link}
     * 唯一键，挂接一次全 fleet 生效，不需逐设备配映射（2026-10-21，固件 Forms 实有 thirdPid）。
     * <p>empty = 未挂接/已解挂（调用方回落 classId 映射）。</p>
     */
    public Optional<String> resolveSkuByProduct(String jiangyiProductId) {
        if (jiangyiProductId == null || jiangyiProductId.isBlank()) {
            return Optional.empty();
        }
        ApiResponse<String> resp = get("/internal/v1/jiangyi/skus/by-product/{productId}",
                String.class, jiangyiProductId);
        String skuId = resp == null ? null : resp.data();
        return Optional.ofNullable(skuId == null || skuId.isBlank() ? null : skuId);
    }

    /**
     * 设备全量映射列表（2026-10-19 真机实证：开机序列固定调
     * floor/getDeviceSellProductClassIds，需返回该设备全部在售 classId——
     * trade 侧已有 GET /class-mappings?deviceId= 全量端点，此处按数组反序列化，
     * ACTIVE 过滤由调用方做（列表含 DISABLED 行）。
     */
    public java.util.List<MappingView> listMappings(String deviceId) {
        ApiResponse<MappingView[]> resp = get("/internal/v1/jiangyi/class-mappings?deviceId={deviceId}",
                MappingView[].class, deviceId);
        return resp == null || resp.data() == null
                ? java.util.List.of() : java.util.Arrays.asList(resp.data());
    }

    /**
     * 设备补货表（floor/getFloorByDevice 的数据源，2026-10-21）：
     * trade 侧按 ACTIVE 映射 + 挂接 + 现行售价 + 货道库存组装，gateway 只负责信封转换。
     * <p>异常原样抛出——调用方要区分「真没商品」与「trade 不可达」，不得吞成空表。</p>
     */
    public java.util.List<FloorGoodsView> listFloorGoods(String deviceId) {
        ApiResponse<FloorGoodsView[]> resp = get(
                "/internal/v1/jiangyi/devices/{deviceId}/floor-goods", FloorGoodsView[].class, deviceId);
        return resp == null || resp.data() == null
                ? java.util.List.of() : java.util.Arrays.asList(resp.data());
    }

    // ---------- 超时面 ----------

    /** 识别未成功的成因；🔴 字面量须与 trade {@code JiangyiRecognitionTimeoutService} 的解析一致。 */
    public static final String CAUSE_CLASS_MAPPING_MISS = "CLASS_MAPPING_MISS";
    public static final String CAUSE_EMPTY_FORMS = "EMPTY_FORMS";
    public static final String CAUSE_DEVICE_ERROR_ORDER = "DEVICE_ERROR_ORDER";
    public static final String CAUSE_NO_REPORT = "NO_REPORT";

    /**
     * 识别未成功 → 即时转人工审核；返回 false = 会话不在此状态（幂等）。
     *
     * <p>{@code cause} 决定争议单 fail_reason 与异常单类型（字面量须与 trade
     * {@code JiangyiRecognitionTimeoutService.CAUSE_*} 一致，改一侧必须改另一侧）；
     * {@code vendorDetail} 放设备原文（uploadOrderError 的 errorMsg）或未命中的 classId，
     * 由 trade 侧截断后写进 reason/detail——排查「厂商到底回了什么」只靠这两个参数。
     */
    public boolean recognizeTimeout(String sessionId, String deviceId, String cause, String vendorDetail) {
        ApiResponse<Boolean> resp = post(
                "/internal/v1/jiangyi/sessions/{sessionId}/recognize-timeout"
                        + "?deviceId={deviceId}&cause={cause}&vendorDetail={vendorDetail}",
                null, Boolean.class, sessionId, compactParam(deviceId), compactParam(cause),
                compactParam(vendorDetail));
        return Boolean.TRUE.equals(resp == null ? null : resp.data());
    }

    /** 查询参数取值：设备原文不可信，先截到 200 再拼 URL，避免超长请求行被容器拒收。 */
    private static String compactParam(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        return v.length() <= 200 ? v : v.substring(0, 200);
    }

    /** 开锁失败通知（trade markOpenDoorFailed 幂等：非 OPENING no-op；AU-060 带上报设备做归属校验）。 */
    public void postOpenFailed(String sessionId, String reason, String deviceId) {
        post("/internal/v1/sessions/{sessionId}/open-failed",
                new OpenDoorFailedRequest(reason, deviceId), sessionId);
    }

    // ---------- 模型同步面（CB-023） ----------

    /** 设备 downloadModelNotify 回执转发：trade 回填 model_name/classes_version + deployment CONFIRMED。 */
    public void modelConfirmed(String deviceId, String modelName) {
        post("/internal/v1/jiangyi/devices/{deviceId}/model-confirmed",
                new ModelConfirmedRequest(modelName), deviceId);
    }

    /** 模型下发超时（watchdog MODEL kind）：trade deployment → FAILED（幂等：非 SENT no-op）。 */
    public void modelPushTimeout(long deploymentId, String reason) {
        post("/internal/v1/jiangyi/model-deployments/{deploymentId}/push-timeout",
                new ModelPushTimeoutRequest(reason), deploymentId);
    }

    /** 将邑云学习完成回调转发（无鉴权公开面收到的，trade 侧凭 finishNotifyId 一次性凭据防伪造）。 */
    public void gatherFinishNotify(String finishNotifyId, String msg) {
        post("/internal/v1/jiangyi/gather-finish-notify",
                new GatherFinishNotifyRequest(finishNotifyId, msg));
    }

    /** 设备视频上报转发（CB-024 §4.2.14）：trade 侧 uk(order_no, serial_num) upsert 幂等落库。 */
    public void orderVideoReport(String deviceId, String orderNo, Integer serialNum,
                                 Integer videoQuantity, java.util.List<String> videoUrls) {
        post("/internal/v1/jiangyi/order-video-report",
                new OrderVideoReportRequest(deviceId, orderNo, serialNum, videoQuantity, videoUrls));
    }

    /**
     * 信号强度转发（V363，图2 对照）：§4.2.15 getDeviceSimInfo 的 signalStrength 0-4
     * → trade device_info.signal_strength 落库。转发失败仅影响展示，调用方记 warn 不重试。
     */
    public void deviceSignal(String deviceId, Integer signalStrength) {
        post("/internal/v1/jiangyi/devices/{deviceId}/signal",
                new DeviceSignalRequest(signalStrength), deviceId);
    }

    /** 与 trade JiangyiInternalController.OrderVideoReportRequest 对齐。 */
    record OrderVideoReportRequest(String deviceId, String orderNo, Integer serialNum,
                                   Integer videoQuantity, java.util.List<String> videoUrls) {}

    /** 与 trade JiangyiInternalController.SignalRequest 对齐。 */
    record DeviceSignalRequest(Integer signalStrength) {}

    /** 与 trade JiangyiInternalController.ModelConfirmedRequest 对齐。 */
    record ModelConfirmedRequest(String modelName) {}

    /** 与 trade JiangyiInternalController.ModelPushTimeoutRequest 对齐。 */
    record ModelPushTimeoutRequest(String reason) {}

    /** 与 trade JiangyiInternalController.GatherFinishNotifyRequest 对齐。 */
    record GatherFinishNotifyRequest(String finishNotifyId, String msg) {}

    /** 与 trade SessionInternalController.OpenDoorFailedRequest 对齐（AU-060 增 deviceId）。 */
    record OpenDoorFailedRequest(String reason, String deviceId) {}

    // ---------- 转发面（上报归一化用） ----------

    /** 门状态转发（gateway 合成 door-event：模式一无关门上报，识别前先 CLOSED）。 */
    public SessionDto doorEvent(DoorEventRequest request) {
        ApiResponse<SessionDto> resp = post("/internal/v1/sessions/door-event",
                request, SessionDto.class);
        return resp == null ? null : resp.data();
    }

    /** 识别结果转发（edge-results 结算链路）。 */
    public VisionResultIngestResponseDto edgeResults(VisionRecognitionResultDto result) {
        ApiResponse<VisionResultIngestResponseDto> resp = post("/internal/v1/vision/edge-results",
                result, VisionResultIngestResponseDto.class);
        return resp == null ? null : resp.data();
    }

    /**
     * 识别上报原样留档（CB-062，V396 {@code jiangyi_recognition_report}）：一到达一行、
     * 含整段 forms 原文与我方归一化结果。🔴 与结算解耦——失败由调用方吞掉记 warn，
     * 存档缺行不得把可结算单拖成 DISPUTED。
     */
    public void postRecognitionReport(RecognitionReportRequest report) {
        post("/internal/v1/jiangyi/recognition-reports", report);
    }

    /** 与 trade JiangyiInternalController.RecognitionReportRequest 对齐（formsJson/itemsJson 为原文 JSON 串）。 */
    public record RecognitionReportRequest(String deviceId, String orderNo, String transactionId,
                                           String payType, String formsJson, String itemsJson,
                                           Integer formCount, Integer itemCount,
                                           Long vendorAmountCents, String outcome,
                                           java.time.Instant reportedAt) {}

    // ---------- 实现 ----------

    private <T> ApiResponse<T> get(String path, Class<T> dataType, Object... uriVars) {
        return restClient.get()
                .uri(path, uriVars)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .retrieve()
                .body(ParameterizedTypeReference.forType(apiResponseType(dataType)));
    }

    private void post(String path, Object body, Object... uriVars) {
        // 2026-10-09 联调缺陷⑦：无 body 的 POST（wsOnline/wsOffline/tokenIssued）传 null
        // 会 NPE（RestClient body(null) → getClass()），导致 WS 在线回执静默失败、
        // trade 开门校验恒判设备离线。body 为 null 时不设请求体。
        var spec = restClient.post()
                .uri(path, uriVars)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key());
        if (body != null) {
            spec = spec.body(body);
        }
        spec.retrieve().toBodilessEntity();
    }

    private <T> ApiResponse<T> post(String path, Object body, Class<T> dataType, Object... uriVars) {
        // 缺陷⑦同款：无 body 的 POST（recognizeTimeout）传 null 会 NPE，body 为 null 时不设请求体。
        var spec = restClient.post()
                .uri(path, uriVars)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key());
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.retrieve()
                .body(ParameterizedTypeReference.forType(apiResponseType(dataType)));
    }

    // ---------- 视图 ----------

    /** jiangyi_device 行视图（与 trade JiangyiInternalController 返回结构对应）。 */
    public record JiangyiDeviceView(String deviceId, String deviceSn, String identifier,
                                    String modelName, String classesVersion, String status,
                                    Long tokenVersion, Instant tokenIssuedAt,
                                    Instant lastWsOnlineAt, Boolean authEnabled) {}

    /**
     * 运营告警上报（fire-and-forget，审计 A1/P0-3）：走 trade 边缘告警端点，
     * alertType 自定义、deviceId 可空。失败只记日志不影响主流程。
     */
    public void postOpsAlert(String alertType, String deviceId, String message) {
        try {
            post("/internal/v1/ops-alerts", Map.of(
                    "alertType", alertType,
                    "deviceId", deviceId == null ? "" : deviceId,
                    "message", message == null ? "" : message));
        } catch (Exception e) {
            log.warn("ops alert post failed type={} deviceId={}", alertType, deviceId, e);
        }
    }

    /** jiangyi_class_mapping 行视图（只暴露解析所需字段）。 */
    public record MappingView(Integer classId, String textName, String skuId,
                              String status, String source) {}

    /**
     * 设备补货表行（与 trade JiangyiInternalController.FloorGoodsResponse 对齐）。
     * salePrice 单位分，取我方现行售价（与结算同源，A 档）；thirdPid 无挂接时为 0。
     */
    public record FloorGoodsView(Integer classId, Integer thirdPid, String productName,
                                 Integer salePrice, Integer stock) {}
}
