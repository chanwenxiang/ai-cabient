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
 * 将邑模型下发入口（CB-023，V16 §4.3.2.9）：trade 校验（BOUND/机型匹配/幂等）后路由至此，
 * gateway 经 WS 下发 {@code {msgType:"updateModel", msgContent:{quantity,modelUrl,textUrl,modelName}}}。
 *
 * <p>回执链：设备 WS 上行 {@code downloadModelNotify}（V16 §4.2.5，成功后机器端重启）→
 * 本网关转发 trade {@code /internal/v1/jiangyi/devices/{deviceId}/model-confirmed}；
 * {@value #MODEL_PUSH_TIMEOUT_MS} 无回执由 SessionWatchdog 置 deployment FAILED。</p>
 *
 * <p>鉴权：/internal/** 由 InternalApiAuthInterceptor 统一校验（GatewayWebConfig）。</p>
 */
@RestController
@RequestMapping("/internal/v1/jiangyi")
public class ModelPushController {

    private static final Logger log = LoggerFactory.getLogger(ModelPushController.class);

    /** 模型下发回执超时：下载 rknn（数 MB～数十 MB）+ 重启，远长于开门 15s。 */
    static final long MODEL_PUSH_TIMEOUT_MS = 600_000L;

    private final TradeInternalClient tradeInternalClient;
    private final DeviceWebSocketHandler deviceWebSocketHandler;
    private final CommandTracker commandTracker;

    public ModelPushController(TradeInternalClient tradeInternalClient,
                               DeviceWebSocketHandler deviceWebSocketHandler,
                               CommandTracker commandTracker) {
        this.tradeInternalClient = tradeInternalClient;
        this.deviceWebSocketHandler = deviceWebSocketHandler;
        this.commandTracker = commandTracker;
    }

    @PostMapping("/devices/{deviceId}/model-push")
    public void modelPush(@PathVariable("deviceId") String deviceId,
                          @RequestBody ModelPushRequest request) {
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelName required");
        }
        if (request.deploymentId() == null || request.deploymentId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deploymentId required");
        }
        TradeInternalClient.JiangyiDeviceView device = tradeInternalClient.device(deviceId).orElse(null);
        if (device == null || !"BOUND".equals(device.status())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        String identifier = device.identifier();
        if (!deviceWebSocketHandler.isOnline(identifier)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备不在线");
        }
        boolean sent = deviceWebSocketHandler.sendModelUpdate(identifier, request.modelName(),
                request.modelUrl(), request.textUrl(), request.quantity() == null ? 0 : request.quantity());
        if (!sent) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型下发失败");
        }
        // tracker key = deploymentId（同一模型可多次下发，sessionId 语义在此即 deploymentId）
        commandTracker.register(CommandTracker.Kind.MODEL, String.valueOf(request.deploymentId()),
                deviceId, identifier);
        log.info("jiangyi model-push dispatched deviceId={} identifier={} modelName={} deploymentId={}",
                deviceId, identifier, request.modelName(), request.deploymentId());
    }

    /** 与 trade JiangyiModelSyncService.PushRequest 对齐（trade 已完成 BOUND/机型/幂等校验）。 */
    public record ModelPushRequest(Long deploymentId, String modelName, String modelUrl,
                                   String textUrl, Integer quantity) {}
}
