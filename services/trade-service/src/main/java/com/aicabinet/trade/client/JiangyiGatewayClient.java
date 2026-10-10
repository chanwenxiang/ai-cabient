package com.aicabinet.trade.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.common.security.InternalApiProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * trade → jiangyi-gateway 内部调用（CB-023）：模型下发面。
 * 与 {@link DeviceServiceClient} 的 open-door 路由同构（INTERNAL_API_KEY 鉴权）；
 * 独立成类避免 DeviceServiceClient 膨胀——模型同步是运营动作不是交易链路。
 */
@Component
public class JiangyiGatewayClient {

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
}
