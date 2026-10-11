package com.aicabinet.trade.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.trade.service.JiangyiDeviceDirectory;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * 开门指令路由（CB-022 改造点，方案 §4.1 时序 1）：
 * <ul>
 *   <li>将邑已绑定柜（{@link JiangyiDeviceDirectory#isJiangyi}）→ jiangyi-gateway
 *       {@code /internal/v1/jiangyi/devices/{deviceId}/open-door}（WS 下发 openDoor，
 *       msgContent=sessionId）；<b>不重试</b>——重复下发可能双开门，失败直接走
 *       markOpenDoorFailed 由用户重扫；</li>
 *   <li>其余柜 → 既有 device-service 链路（编程式 Retry 保留，MQTT 语义幂等）；
 *       ⚠️ 不用 @Retry 注解：分支方法被入口自调用时 AOP 代理不生效（静默失效），
 *       编程式 RetryRegistry 与 yml instances.deviceService 同名等价；</li>
 *   <li>operatorMode 分流：补货开门（stock）走将邑 sendOpenStockDoor（2026-10-11 收尾E，
 *       协议 §4.3.2.4，msgContent=补货会话 id）；运维开门（ADM 会话）对将邑柜仍 501（协议无此概念）。</li>
 * </ul>
 */
@Component
public class DeviceServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DeviceServiceClient.class);

    private final RestClient restClient;
    private final InternalApiProperties internalApiProperties;
    private final JiangyiDeviceDirectory jiangyiDeviceDirectory;
    private final RestClient jiangyiGatewayClient;
    private final RetryRegistry retryRegistry;

    public DeviceServiceClient(@Value("${aicabinet.device-service.url:http://localhost:8081}") String baseUrl,
                               @Value("${aicabinet.jiangyi-gateway.url:http://localhost:8083}") String jiangyiGatewayUrl,
                               InternalApiProperties internalApiProperties,
                               JiangyiDeviceDirectory jiangyiDeviceDirectory,
                               RetryRegistry retryRegistry) {
        this.restClient = InternalRestClientFactory.create(baseUrl);
        this.jiangyiGatewayClient = InternalRestClientFactory.create(jiangyiGatewayUrl);
        this.internalApiProperties = internalApiProperties;
        this.jiangyiDeviceDirectory = jiangyiDeviceDirectory;
        this.retryRegistry = retryRegistry;
    }

    /** 入口分流。 */
    public void requestOpenDoor(String sessionId, String deviceId, Long userId, boolean operatorMode) {
        if (jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            requestJiangyiOpenDoor(sessionId, deviceId, userId, operatorMode, false);
        } else {
            requestDeviceServiceOpenDoor(sessionId, deviceId, userId, operatorMode);
        }
    }

    public void requestOpenDoorOperator(String sessionId, String deviceId, Long userId) {
        requestOpenDoor(sessionId, deviceId, userId, true);
    }

    /**
     * 补货开门（将邑柜真路由，收尾E）：WS 下发 {@code sendOpenStockDoor}，
     * msgContent=补货会话 id（协议枢纽 orderNo≡sessionId 不变）；柜机生成补货视频后
     * 经 §4.2.14 上报（orderNo 同值），落 {@code jiangyi_order_video} 台账。
     */
    public void requestOpenDoorStock(String sessionId, String deviceId, Long userId) {
        if (jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            requestJiangyiOpenDoor(sessionId, deviceId, userId, false, true);
        } else {
            requestDeviceServiceOpenDoor(sessionId, deviceId, userId, true);
        }
    }

    /** 将邑柜开门：单次下发不重试（双开门风险 > 重试收益，CB-022 决策）。 */
    private void requestJiangyiOpenDoor(String sessionId, String deviceId, Long userId,
                                        boolean operatorMode, boolean stock) {
        log.info("request jiangyi open door: session={}, device={}, user={}, operator={}, stock={}",
                sessionId, deviceId, userId, operatorMode, stock);
        if (operatorMode && !stock) {
            // 运维开门（ADM 会话）协议无此概念：将邑柜仅支持 购物开门/补货开门 两种
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "将邑柜不支持运维开门");
        }
        jiangyiGatewayClient.post()
                .uri("/internal/v1/jiangyi/devices/{deviceId}/open-door", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new OpenDoorRequest(sessionId, userId, operatorMode, stock))
                .retrieve()
                .toBodilessEntity();
    }

    /** 既有 device-service 链路：编程式重试（与原 @Retry("deviceService") 同名等价）。 */
    private void requestDeviceServiceOpenDoor(String sessionId, String deviceId, Long userId, boolean operatorMode) {
        log.info("request open door: session={}, device={}, user={}, operator={}",
                sessionId, deviceId, userId, operatorMode);
        retryRegistry.retry("deviceService").executeRunnable(() -> restClient.post()
                .uri("/internal/v1/devices/{deviceId}/open-door", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new OpenDoorRequest(sessionId, userId, operatorMode, false))
                .retrieve()
                .toBodilessEntity());
    }

    public String requestSetTargetTemp(String deviceId, int targetTempC) {
        log.info("request set target temp: device={}, target={}", deviceId, targetTempC);
        SetTargetTempResponse body = restClient.post()
                .uri("/internal/v1/devices/{deviceId}/set-target-temp", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new SetTargetTempRequest(targetTempC))
                .retrieve()
                .body(SetTargetTempResponse.class);
        if (body == null || body.commandId() == null) {
            throw new IllegalStateException("device-service set-target-temp returned empty body");
        }
        return body.commandId();
    }

    public String requestOpsCommand(String deviceId, String command) {
        log.info("request ops command: device={}, command={}", deviceId, command);
        OpsCommandResponse body = restClient.post()
                .uri("/internal/v1/devices/{deviceId}/ops-command", deviceId)
                .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                .body(new OpsCommandRequest(command))
                .retrieve()
                .body(OpsCommandResponse.class);
        if (body == null || body.commandId() == null) {
            throw new IllegalStateException("device-service ops-command returned empty body");
        }
        return body.commandId();
    }

    /**
     * @param stock true=补货开门（将邑 sendOpenStockDoor，msgContent=补货会话 id）
     */
    record OpenDoorRequest(String sessionId, Long userId, boolean operatorMode, boolean stock) {}
    record SetTargetTempRequest(int targetTempC) {}
    record SetTargetTempResponse(String commandId) {}
    record OpsCommandRequest(String command) {}
    record OpsCommandResponse(String commandId, String command) {}
}
