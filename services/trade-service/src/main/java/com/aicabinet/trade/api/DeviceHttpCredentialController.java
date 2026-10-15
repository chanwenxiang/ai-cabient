package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.DeviceHttpCredentialService;
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
 * 设备 HTTP 独立凭据（审计 A1/P0-2，2026-10-15 拍板块 A）：设备可达内部端点
 * （OTA check/progress、screen-content、ad-play、sessions/video*）一机一密的
 * 签发/吊销/状态。permission 口径与 MQTT 凭据一致（ops:device:edit / ops:device:list，
 * 铁律 66：同一设备面板取址统一）。
 *
 * <p>明文 secret 仅在签发响应返回一次（SOP：现场装机注入 edge APK 后不可再取）。</p>
 */
@RestController
@RequestMapping("/api/v2/ops/admin/devices/{deviceId}/http-credential")
public class DeviceHttpCredentialController {

    private static final String BIZ_DEVICE_HTTP_CREDENTIAL = "DEVICE_HTTP_CREDENTIAL";

    private final DeviceHttpCredentialService credentialService;
    private final AdminAuditService auditService;

    public DeviceHttpCredentialController(DeviceHttpCredentialService credentialService,
                                          AdminAuditService auditService) {
        this.credentialService = credentialService;
        this.auditService = auditService;
    }

    /** 签发/轮换：明文 secret 仅本次响应可见。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping
    public ApiResponse<Map<String, Object>> issue(HttpServletRequest request,
                                                  @PathVariable String deviceId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        Long operatorId = operatorId(request);
        DeviceHttpCredentialService.IssuedCredential issued =
                credentialService.issue(deviceId, operatorId);
        boolean explicitRotate = body != null && "rotate".equalsIgnoreCase(body.get("mode"));
        auditService.appendLog(operatorId, "DEVICE_HTTP_CREDENTIAL_ISSUE", BIZ_DEVICE_HTTP_CREDENTIAL,
                deviceId, explicitRotate ? "轮换" : "签发");
        // 响应体含明文 secret：走 HTTPS/内网；日志与审计均不落 secret。
        return ApiResponse.ok(Map.of(
                "deviceId", issued.deviceId(),
                "secret", issued.secret(),
                "issuedAt", issued.issuedAt().toString()));
    }

    /** 吊销：状态置 REVOKED、hash 清空，设备面 HTTP 即刻 401。 */
    @RequiresPermissions("ops:device:edit")
    @DeleteMapping
    public ApiResponse<Map<String, Object>> revoke(HttpServletRequest request,
                                                   @PathVariable String deviceId,
                                                   @RequestParam(name = "reason", required = false) String reason) {
        Long operatorId = operatorId(request);
        boolean revoked = credentialService.revoke(deviceId, operatorId, reason);
        auditService.appendLog(operatorId, "DEVICE_HTTP_CREDENTIAL_REVOKE", BIZ_DEVICE_HTTP_CREDENTIAL,
                deviceId, revoked ? "已吊销；原因=" + (reason == null ? "" : reason) : "重复吊销（无变化）");
        return ApiResponse.ok(Map.of("deviceId", deviceId, "revoked", revoked));
    }

    /** 状态查询：永不返回明文 secret。 */
    @RequiresPermissions("ops:device:list")
    @GetMapping
    public ApiResponse<Map<String, Object>> status(@PathVariable String deviceId) {
        DeviceHttpCredentialService.CredentialStatus s = credentialService.status(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该设备尚未签发 HTTP 凭据"));
        return ApiResponse.ok(Map.of(
                "deviceId", s.deviceId(),
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
}
