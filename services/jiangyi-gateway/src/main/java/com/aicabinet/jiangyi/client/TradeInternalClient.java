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

    /** WS 在线回执。 */
    public void wsOnline(String deviceId) {
        post("/internal/v1/jiangyi/devices/{deviceId}/ws-online", null, deviceId);
    }

    // ---------- 映射面 ----------

    /** ACTIVE 映射点查；empty = 未命中（调用方 fail-closed → recognizeTimeout）。 */
    public Optional<MappingView> resolveMapping(String deviceId, Integer classId) {
        ApiResponse<MappingView> resp = get("/internal/v1/jiangyi/class-mappings/{deviceId}/{classId}",
                MappingView.class, deviceId, classId);
        return Optional.ofNullable(resp == null ? null : resp.data());
    }

    // ---------- 超时面 ----------

    /** 识别映射未命中 → 即时转人工审核；返回 false = 会话不在此状态（幂等）。 */
    public boolean recognizeTimeout(String sessionId) {
        ApiResponse<Boolean> resp = post("/internal/v1/jiangyi/sessions/{sessionId}/recognize-timeout",
                null, Boolean.class, sessionId);
        return Boolean.TRUE.equals(resp == null ? null : resp.data());
    }

    /** 开锁失败通知（trade markOpenDoorFailed 幂等：非 OPENING no-op）。 */
    public void postOpenFailed(String sessionId, String reason) {
        post("/internal/v1/sessions/{sessionId}/open-failed",
                new OpenDoorFailedRequest(reason), sessionId);
    }

    /** 与 trade SessionInternalController.OpenDoorFailedRequest 对齐。 */
    record OpenDoorFailedRequest(String reason) {}

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

    // ---------- 实现 ----------

    private <T> ApiResponse<T> get(String path, Class<T> dataType, Object... uriVars) {
        return restClient.get()
                .uri(path, uriVars)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .retrieve()
                .body(ParameterizedTypeReference.forType(apiResponseType(dataType)));
    }

    private void post(String path, Object body, Object... uriVars) {
        restClient.post()
                .uri(path, uriVars)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private <T> ApiResponse<T> post(String path, Object body, Class<T> dataType, Object... uriVars) {
        return restClient.post()
                .uri(path, uriVars)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(body)
                .retrieve()
                .body(ParameterizedTypeReference.forType(apiResponseType(dataType)));
    }

    // ---------- 视图 ----------

    /** jiangyi_device 行视图（与 trade JiangyiInternalController 返回结构对应）。 */
    public record JiangyiDeviceView(String deviceId, String deviceSn, String identifier,
                                    String modelName, String classesVersion, String status,
                                    Long tokenVersion, Instant tokenIssuedAt,
                                    Instant lastWsOnlineAt) {}

    /** jiangyi_class_mapping 行视图（只暴露解析所需字段）。 */
    public record MappingView(Integer classId, String textName, String skuId,
                              String status, String source) {}
}
