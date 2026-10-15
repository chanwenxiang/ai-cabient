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

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 同 SN 签发突发判定窗口与阈值：token exp 7 天，正常重领极少（重启/吊销后 1 次）。 */
    private static final int BURST_WINDOW_MINUTES = 10;
    private static final int BURST_THRESHOLD = 3;
    /** 未激活请求/突发告警的同 SN 限频（防刷屏；内存态，重启清零可接受——告警是概率防御）。 */
    private static final long ALERT_SUPPRESS_MS = 10 * 60 * 1000L;

    private final DeviceTokenService deviceTokenService;
    private final TradeInternalClient tradeInternalClient;
    private final boolean keyConfiguredMarker;
    /** 同 SN 最近签发时间窗（并发场景下近似即可，不追求精确）。 */
    private final Map<String, Deque<Instant>> issueWindowBySn = new ConcurrentHashMap<>();
    private final Map<String, Instant> alertSuppressedUntil = new ConcurrentHashMap<>();

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
        // 审计 A1/P0-3 激活制（2026-10-15 拍板）：SN 为出厂可枚举标识，未激活设备一律拒发
        // ——仿冒 SN 拿不到 token，即进不了识别上报链。未激活请求本身=可疑探测，HIGH 告警。
        if (!Boolean.TRUE.equals(device.authEnabled())) {
            log.info("jiangyi token issue rejected: deviceSn={} deviceId={} not activated",
                    deviceSn, device.deviceId());
            alertSuppressed("JIANGYI_TOKEN_UNACTIVATED", device.deviceId(), deviceSn,
                    "未激活将邑设备请求 token（SN 可能为仿冒探测）");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "设备未激活，请联系运营");
        }
        if (isIssueBurst(deviceSn)) {
            // 正常设备 exp 7 天 + 重启才重领；窗口内连续多签=SN 疑似泄露/被仿冒，HIGH 告警
            //（不拒发：也可能真是设备反复重启，宁可告警人工判断，不误伤营业）
            alertSuppressed("JIANGYI_TOKEN_ISSUE_BURST", device.deviceId(), deviceSn,
                    "同一设备短时间连续领取 token，SN 可能泄露");
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

    /** 滑窗突发判定：窗口内第 threshold 次起返回 true（记录含本次）。 */
    private boolean isIssueBurst(String deviceSn) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(BURST_WINDOW_MINUTES * 60L);
        Deque<Instant> window = issueWindowBySn.computeIfAbsent(deviceSn, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(now);
            while (!window.isEmpty() && window.peekFirst().isBefore(windowStart)) {
                window.removeFirst();
            }
            return window.size() >= BURST_THRESHOLD;
        }
    }

    /** 同 SN 告警限频：抑制窗内不重发（防刷屏）。 */
    private void alertSuppressed(String type, String deviceId, String deviceSn, String message) {
        Instant now = Instant.now();
        Instant until = alertSuppressedUntil.compute(deviceSn, (k, prev) ->
                prev != null && prev.isAfter(now) ? prev : now.plusMillis(ALERT_SUPPRESS_MS));
        if (!until.equals(now.plusMillis(ALERT_SUPPRESS_MS))) {
            return;
        }
        tradeInternalClient.postOpsAlert(type, deviceId, deviceSn + "：" + message);
    }

    private static Map<String, Object> envelope(int status, String msg, Object data) {
        return Map.of("status", status, "msg", msg, "data", data == null ? Map.of() : data);
    }

    /** V16 §4.2.3 请求体：{deviceSn}。 */
    public record TokenIssueRequest(String deviceSn) {}
}
