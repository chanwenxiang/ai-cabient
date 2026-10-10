package com.aicabinet.trade.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.common.security.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * trade → jiangyi-gateway 内部调用（CB-023）：模型下发面。
 * 与 {@link DeviceServiceClient} 的 open-door 路由同构（INTERNAL_API_KEY 鉴权）；
 * 独立成类避免 DeviceServiceClient 膨胀——模型同步是运营动作不是交易链路。
 */
@Component
public class JiangyiGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(JiangyiGatewayClient.class);

    private final RestClient gatewayClient;
    private final InternalApiProperties internalApiProperties;

    public JiangyiGatewayClient(@Value("${aicabinet.jiangyi-gateway.url:http://localhost:8083}") String gatewayUrl,
                                InternalApiProperties internalApiProperties) {
        this.gatewayClient = InternalRestClientFactory.create(gatewayUrl);
        this.internalApiProperties = internalApiProperties;
    }

    /** 下发模型（V16 §4.3.2.9 updateModel）；异常向上抛由编排层转 deployment FAILED。 */
    public void modelPush(String deviceId, long deploymentId, String modelName,
                          String modelUrl, String textUrl, Integer quantity) {
        gatewayClient.post()
                .uri("/internal/v1/jiangyi/devices/{deviceId}/model-push", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new ModelPushRequest(deploymentId, modelName, modelUrl, textUrl, quantity))
                .retrieve()
                .toBodilessEntity();
    }

    /** 与 gateway ModelPushController.ModelPushRequest 对齐。 */
    public record ModelPushRequest(Long deploymentId, String modelName, String modelUrl,
                                   String textUrl, Integer quantity) {}

    /**
     * OSS 预签名 GET URL（CB-029 视频复核读路径）。
     *
     * <p>三分支：本桶 → {@link PresignTarget#signed}；非本桶（设备上报外部地址）→
     * {@link PresignTarget#external}；网关不可用 / 未配置 → {@link Optional#empty()}（调用方 fail-closed）。</p>
     */
    public Optional<PresignTarget> presignGet(String ref) {
        PresignResponse response;
        try {
            response = gatewayClient.post()
                    .uri("/internal/v1/jiangyi/oss/presign-get")
                    .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                    .body(new PresignRequest(ref))
                    .retrieve()
                    .body(PresignResponse.class);
        } catch (RestClientException e) {
            log.warn("jiangyi gateway presign-get failed: {}", e.getMessage());
            return Optional.empty();
        }
        if (response == null) {
            return Optional.empty();
        }
        if (response.ok() && response.url() != null && !response.url().isBlank()) {
            return Optional.of(PresignTarget.signed(response.url(), response.expiration()));
        }
        if (response.external()) {
            return Optional.of(PresignTarget.externalRef());
        }
        log.warn("jiangyi gateway presign-get refused: {}", response.reason());
        return Optional.empty();
    }

    /** 预签名结果：signed=有短时效 URL；external=不在本桶，原样使用设备上报的地址。 */
    public record PresignTarget(String url, String expiration, boolean external) {

        public static PresignTarget signed(String url, String expiration) {
            return new PresignTarget(url, expiration, false);
        }

        /** 🔴 名字不能是 {@code external()}：与 record 分量 {@code external} 同名会被判为非法访问器。 */
        public static PresignTarget externalRef() {
            return new PresignTarget(null, null, true);
        }
    }

    public record PresignRequest(String ref) {}

    /** 与 gateway OssPresignController.PresignResponse 字段对齐。 */
    public record PresignResponse(boolean ok, boolean external, String url, String expiration, String reason) {}
}
