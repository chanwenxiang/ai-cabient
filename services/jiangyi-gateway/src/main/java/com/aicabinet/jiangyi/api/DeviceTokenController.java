package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceTokenService;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.JiangyiDeviceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 将邑设备 token 签发（CB-022，方案 §3）：设备启动后以 SN 换 token
 * （V16 §4.2.3「设备获取商户服务器返回的 token」）。
 *
 * <p>响应为将邑协议 envelope：{@code {status, msg, data:{token}}}（文档表格 7），
 * <b>不是</b> ApiResponse。失败语义：404 未登记 / 403 未绑定或退役 / 503 密钥未配置
 * 或 trade 不可达（fail-closed）。签发成功后回执 trade 记 token_issued_at。</p>
 *
 * <p>本端点是设备唯一免鉴权入口（GatewayWebConfig 排除），换取 token 后所有
 * /jiangyi/api/** 请求走 DeviceAuthInterceptor。</p>
 */
@RestController
public class DeviceTokenController {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenController.class);

    private final DeviceTokenService deviceTokenService;
    private final TradeInternalClient tradeInternalClient;
    private final boolean keyConfiguredMarker;

    public DeviceTokenController(DeviceTokenService deviceTokenService,
                                 TradeInternalClient tradeInternalClient,
                                 @Value("${aicabinet.jiangyi.jwt-secret:}") String jwtSecret) {
        this.deviceTokenService = deviceTokenService;
        this.tradeInternalClient = tradeInternalClient;
        this.keyConfiguredMarker = jwtSecret != null && !jwtSecret.isBlank();
    }

    /**
     * 主路径照抄 V16 §4.2.3（设备固件按 domain+"token/openDoorDeviceStatus" 硬拼）；
     * {@code /jiangyi/api/token} 为方案 §3 简短别名（文档笔误容错，两路同语义）。
     */
    @PostMapping({"/jiangyi/api/token/openDoorDeviceStatus", "/jiangyi/api/token"})
    public Map<String, Object> issueToken(@RequestBody TokenIssueRequest request) {
        String deviceSn = request == null ? null : request.deviceSn();
        if (deviceSn == null || deviceSn.isBlank()) {
            return envelope(400, "deviceSn required", null);
        }
        if (!keyConfiguredMarker) {
            // fail-closed：密钥未配置时不做任何签发尝试（方案 §3）
            log.warn("jiangyi token issue rejected: JIANGYI_JWT_SECRET not configured");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "token 服务未配置");
        }
        JiangyiDeviceView device = tradeInternalClient.deviceBySn(deviceSn).orElse(null);
        if (device == null) {
            log.info("jiangyi token issue rejected: unknown deviceSn={}", deviceSn);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备未登记");
        }
        if (!"BOUND".equals(device.status())) {
            log.info("jiangyi token issue rejected: deviceSn={} status={}", deviceSn, device.status());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "设备未绑定或已退役");
        }
        String token;
        try {
            token = deviceTokenService.issue(device);
        } catch (IllegalStateException e) {
            // 密钥强度不足等 fail-closed 场景
            log.warn("jiangyi token issue rejected: deviceSn={} reason={}", deviceSn, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        try {
            tradeInternalClient.tokenIssued(device.deviceId());
        } catch (Exception e) {
            // 回执失败不阻断签发（token 已交付；签发时间缺失只影响审计精度）
            log.warn("jiangyi token issued receipt failed deviceId={}", device.deviceId(), e);
        }
        log.info("jiangyi token issued deviceId={} identifier={} sn={}",
                device.deviceId(), device.identifier(), deviceSn);
        // data 附带 identifier：设备/模拟器直接拼 WS 路径 /websocket/device/{identifier}（自描述）
        return envelope(200, "success", Map.of("token", token, "identifier", device.identifier()));
    }

    private static Map<String, Object> envelope(int status, String msg, Object data) {
        return Map.of("status", status, "msg", msg, "data", data == null ? Map.of() : data);
    }

    /** V16 §4.2.3 请求体：{deviceSn}。 */
    public record TokenIssueRequest(String deviceSn) {}
}
