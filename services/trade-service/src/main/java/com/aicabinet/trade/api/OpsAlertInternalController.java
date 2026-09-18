package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.service.OpsAlertDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * H62a：接收 device-service 转发的柜机 edge 告警事件（如 EDGE_QUEUE_ABANDON），
 * 薄转发到 {@link OpsAlertDispatcher#trySend}（飞书 / 钉钉 / 企微 / 通用 Webhook）。
 * 投递失败由 dispatcher 内部记日志，不影响本端点返回。
 */
@RestController
@RequestMapping("/internal/v1/ops-alerts")
public class OpsAlertInternalController {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertInternalController.class);

    private final OpsAlertDispatcher opsAlertDispatcher;

    public OpsAlertInternalController(OpsAlertDispatcher opsAlertDispatcher) {
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    @PostMapping
    public ApiResponse<Void> edgeAlert(@RequestBody EdgeAlertRequest body) {
        String type = body.alertType() == null || body.alertType().isBlank()
                ? "EDGE_ALERT" : body.alertType();
        log.info("edge ops alert received device={} type={}", body.deviceId(), type);
        opsAlertDispatcher.trySend(type, "柜机边缘告警 " + type, body.message(),
                body.deviceId() == null || body.deviceId().isBlank()
                        ? Map.of() : Map.of("deviceId", body.deviceId()));
        return ApiResponse.ok(null);
    }

    /** device-service TradeServiceClient.EdgeAlertBody 对应的请求体。 */
    record EdgeAlertRequest(String alertType, String message, String deviceId) {}
}
