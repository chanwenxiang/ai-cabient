package com.aicabinet.trade.client;

import com.aicabinet.common.constants.CabinetConstants;
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
import org.springframework.web.client.RestClientResponseException;
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
        try {
            jiangyiGatewayClient.post()
                    .uri("/internal/v1/jiangyi/devices/{deviceId}/open-door", deviceId)
                    .header(InternalApiConstants.API_KEY_HEADER, internalApiProperties.key())
                    .body(new OpenDoorRequest(sessionId, userId, operatorMode, stock))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // P3 文案修复（2026-10-12）：网关失败语义直译，不再落「请确认 device-service 在线」
            // 旧链兜底文案（将邑柜失败与 device-service 无关）。503=网关侧判定设备 WS 离线。
            if (e.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "将邑柜机不在线，请稍后再试");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "将邑柜开门指令下发失败，请稍后再试", e);
        }
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

    /**
     * 该设备是否真的接在 device-service 的 MQTT 通道上。
     *
     * <p>将邑柜<b>不接 MQTT</b>（只连 jiangyi-gateway 的 WS）⇒ 调用方在「可选是否下发边缘指令」的
     * 场景（如营业锁机：平台侧已生效，物理锁机对将邑无指令）应先问本方法，
     * 而不是发一条注定超时的指令再回退。
     */
    public boolean usesMqttChannel(String deviceId) {
        return !jiangyiDeviceDirectory.isJiangyi(deviceId);
    }

    public String requestSetTargetTemp(String deviceId, int targetTempC) {
        log.info("request set target temp: device={}, target={}", deviceId, targetTempC);
        if (jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            // 🔴 将邑 V16 下行 17 条里**没有温控消息**：这条指令发出去只会「已下发 → 15s TIMEOUT」。
            // 温控计划/运营设温对将邑柜一律走本拒绝（见 JiangyiCommandCapability）。
            throw jiangyiRefusal(CabinetConstants.MQTT_CMD_SET_TARGET_TEMP);
        }
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
        if (jiangyiDeviceDirectory.isJiangyi(deviceId)) {
            // 将邑柜不走 MQTT：不发「没人订阅」的指令冒充成功（2026-11-23 商户端「重启」实证）。
            // 该走网关的（REBOOT）由 AdminDeviceOpsService 转 JiangyiDeviceOpsService（含业务闸）。
            throw jiangyiRefusal(command);
        }
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
     * 将邑柜收到一条 MQTT 运维指令时的拒绝语义（按 {@link JiangyiCommandCapability} 分档）。
     *
     * <p>🔴 三条文案必须<b>各说各的真话</b>：
     * <ul>
     *   <li>{@code GATEWAY}：能力有，但通道不对 —— 让调用方去对的地方，而不是「不支持」；</li>
     *   <li>{@code PLATFORM_ONLY}：物理动作不存在（将邑柜门锁由开门指令控制），
     *       平台侧已生效，别让运维以为柜子被锁了；</li>
     *   <li>{@code UNSUPPORTED}：协议确实没有，明确拒绝。</li>
     * </ul>
     */
    private ResponseStatusException jiangyiRefusal(String command) {
        JiangyiCommandCapability.Route route;
        try {
            route = JiangyiCommandCapability.routeOf(command);
        } catch (IllegalArgumentException e) {
            return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "将邑柜未声明指令能力：" + command + "（见 JiangyiCommandCapability）");
        }
        return switch (route) {
            case GATEWAY -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "将邑柜指令 " + command + " 须经将邑网关通道下发（不走 MQTT），请从运营台设备卡的将邑面板操作");
            case PLATFORM_ONLY -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "将邑柜协议无 " + command + " 物理指令：营业状态已在平台侧生效，柜门由开门指令控制");
            case UNSUPPORTED -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "将邑柜协议不支持 " + command + "（V16 下行无此消息类型）");
        };
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
