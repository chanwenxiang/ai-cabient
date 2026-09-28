package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.DeviceMqttCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * S1 设备凭据：每设备独立 MQTT 账号（username=deviceId）的签发/吊销/状态。
 *
 * <p>明文 secret 仅在签发响应返回一次；生成器（gen-emqx-auth-bootstrap.ps1）经内部通道
 * 读取 ACTIVE 凭据产出 EMQX bootstrap CSV，EMQX 重建后凭据生效。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin/devices/{deviceId}/mqtt-credential")
public class DeviceMqttCredentialController {

    private static final String BIZ_DEVICE_MQTT_CREDENTIAL = "DEVICE_MQTT_CREDENTIAL";

    private final DeviceMqttCredentialService credentialService;
    private final AdminAuditService auditService;

    public DeviceMqttCredentialController(DeviceMqttCredentialService credentialService,
                                          AdminAuditService auditService) {
        this.credentialService = credentialService;
        this.auditService = auditService;
    }

    /** 签发/轮换：明文 secret 仅本次响应可见（SOP：现场注入后不可再取）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping
    public ApiResponse<Map<String, Object>> issue(HttpServletRequest request,
                                                  @PathVariable String deviceId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        Long operatorId = operatorId(request);
        DeviceMqttCredentialService.IssuedCredential issued =
                credentialService.issue(deviceId, operatorId);
        auditService.appendLog(operatorId, "DEVICE_MQTT_CREDENTIAL_ISSUE", BIZ_DEVICE_MQTT_CREDENTIAL,
                deviceId, rotationWord(issued, body));
        // 响应体含明文 secret：走 HTTPS/内网；日志与审计均不落 secret。
        return ApiResponse.ok(Map.of(
                "deviceId", issued.deviceId(),
                "username", issued.username(),
                "secret", issued.secret(),
                "issuedAt", issued.issuedAt().toString()));
    }

    /** 吊销：状态置 REVOKED，生成器不再输出；EMQX 重建 bootstrap 后凭据失效。 */
    @RequiresPermissions("ops:device:edit")
    @DeleteMapping
    public ApiResponse<Map<String, Object>> revoke(HttpServletRequest request,
                                                   @PathVariable String deviceId,
                                                   @RequestParam(name = "reason", required = false) String reason) {
        Long operatorId = operatorId(request);
        boolean revoked = credentialService.revoke(deviceId, operatorId, reason);
        auditService.appendLog(operatorId, "DEVICE_MQTT_CREDENTIAL_REVOKE", BIZ_DEVICE_MQTT_CREDENTIAL,
                deviceId, revoked ? "已吊销；原因=" + (reason == null ? "" : reason) : "重复吊销（无变化）");
        return ApiResponse.ok(Map.of("deviceId", deviceId, "revoked", revoked));
    }

    /** 状态查询：永不返回明文 secret。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping
    public ApiResponse<Map<String, Object>> status(@PathVariable String deviceId) {
        DeviceMqttCredentialService.CredentialStatus s = credentialService.status(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该设备尚未签发 MQTT 凭据"));
        return ApiResponse.ok(Map.of(
                "deviceId", s.deviceId(),
                "username", s.username(),
                "status", s.status(),
                "issuedAt", String.valueOf(s.issuedAt()),
                "rotatedAt", String.valueOf(s.rotatedAt()),
                "revokedAt", String.valueOf(s.revokedAt()),
                "revokeReason", s.revokeReason() == null ? "" : s.revokeReason()));
    }

    private static Long operatorId(HttpServletRequest request) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (operatorId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证");
        }
        return operatorId;
    }

    private static String rotationWord(DeviceMqttCredentialService.IssuedCredential issued,
                                       Map<String, String> body) {
        boolean explicitRotate = body != null && "rotate".equalsIgnoreCase(body.get("mode"));
        return explicitRotate ? "轮换" : "签发";
    }
}
