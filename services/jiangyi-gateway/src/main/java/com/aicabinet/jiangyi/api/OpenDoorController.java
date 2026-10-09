package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.tracker.CommandTracker;
import com.aicabinet.jiangyi.ws.DeviceWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 将邑开门指令入口（CB-022，方案 §4.1 时序 1）：trade DeviceServiceClient 路由至此
 * （仅 isJiangyi 设备），gateway 经 WS 下发 {@code {msgType:"openDoor", msgContent:<sessionId>}}。
 *
 * <p>语义边界：msgContent 必须 = sessionId（orderNo=我方 sessionId，V16 §4.3.2.7）；
 * 强制开门（空 msgContent）本端点在结构上不可能发出（sessionId 为空直接 400）。
 * 下发成功 ≠ 门已开：物理执行回执走设备 HTTP 上报（uploadLockState/uploadDoorState）；
 * 15s 无回执由 SessionWatchdog 兜底 open-failed。</p>
 *
 * <p>鉴权：/internal/** 由 InternalApiAuthInterceptor 统一校验（GatewayWebConfig）。</p>
 */
@RestController
@RequestMapping("/internal/v1/jiangyi")
public class OpenDoorController {

    private static final Logger log = LoggerFactory.getLogger(OpenDoorController.class);

    private final TradeInternalClient tradeInternalClient;
    private final DeviceWebSocketHandler deviceWebSocketHandler;
    private final CommandTracker commandTracker;

    public OpenDoorController(TradeInternalClient tradeInternalClient,
                              DeviceWebSocketHandler deviceWebSocketHandler,
                              CommandTracker commandTracker) {
        this.tradeInternalClient = tradeInternalClient;
        this.deviceWebSocketHandler = deviceWebSocketHandler;
        this.commandTracker = commandTracker;
    }

    @PostMapping("/devices/{deviceId}/open-door")
    public void openDoor(@PathVariable("deviceId") String deviceId,
                         @RequestBody OpenDoorRequest request) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            // 结构性拒绝强制开门：msgContent 空即强制开门（V16 §4.3.2），一期一律不下发
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId required（强制开门一期不支持）");
        }
        TradeInternalClient.JiangyiDeviceView device = tradeInternalClient.device(deviceId).orElse(null);
        if (device == null || !"BOUND".equals(device.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        String identifier = device.identifier();
        if (!deviceWebSocketHandler.isOnline(identifier)) {
            log.warn("jiangyi open-door: device offline deviceId={} identifier={} sessionId={}",
                    deviceId, identifier, sessionId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备不在线");
        }
        boolean sent = deviceWebSocketHandler.sendOpenDoor(identifier, sessionId);
        if (!sent) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "开门指令下发失败");
        }
        commandTracker.register(CommandTracker.Kind.OPEN, sessionId, deviceId, identifier);
        log.info("jiangyi open-door dispatched deviceId={} identifier={} sessionId={}",
                deviceId, identifier, sessionId);
    }

    /** 与 trade DeviceServiceClient.OpenDoorRequest 字段对齐（operatorMode 在 trade 侧已 501，不会到达）。 */
    public record OpenDoorRequest(String sessionId, Long userId, boolean operatorMode) {}
}
