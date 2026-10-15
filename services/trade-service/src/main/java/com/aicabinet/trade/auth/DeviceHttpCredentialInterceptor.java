package com.aicabinet.trade.auth;

import com.aicabinet.trade.service.DeviceHttpCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备面一机一密拦截器（审计 A1/P0-2，2026-10-15 拍板块 A）。
 *
 * <p>覆盖「设备可达」内部端点（/internal/v1/devices/{id}/ota/check、ota/progress、
 * screen-content、ad-play）：凭据头 X-Device-Id + X-Device-Secret，sha256 恒定时间
 * 比对 device_http_credential（V367），且与路径 {id} 绑定——消除 fleet 共享
 * INTERNAL_API_KEY 硬编 APK 的「任一泄露=全 fleet 权限」。</p>
 *
 * <p>语义：缺头/凭据错=401（不泄露凭据存在性）；路径 deviceId 与凭据不符=403
 * DEVICE_MISMATCH（对齐 VisionInternalController 的绑定语义）。服务间调用
 * （trade↔device↔jiangyi-gateway）不受影响，仍走 InternalApiAuthInterceptor。</p>
 */
public class DeviceHttpCredentialInterceptor implements HandlerInterceptor {

    public static final String DEVICE_ID_HEADER = "X-Device-Id";
    public static final String DEVICE_SECRET_HEADER = "X-Device-Secret";

    /** 匹配 /internal/v1/devices/{id}/...，id 捕获组。 */
    private static final Pattern DEVICE_PATH = Pattern.compile("^/internal/v1/devices/([^/]+)(/.*)?$");

    private final DeviceHttpCredentialService credentialService;

    public DeviceHttpCredentialInterceptor(DeviceHttpCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String deviceId = trimToNull(request.getHeader(DEVICE_ID_HEADER));
        String secret = request.getHeader(DEVICE_SECRET_HEADER);
        if (deviceId == null || secret == null || secret.isBlank()
                || !credentialService.verify(deviceId, secret)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        String pathDeviceId = pathDeviceId(request.getRequestURI());
        if (pathDeviceId != null && !pathDeviceId.equals(deviceId)) {
            // 凭据正确但指向别的设备：与 VisionInternalController P1-5 同语义的绑定防线
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"message\":\"DEVICE_MISMATCH\"}");
            response.setContentType("application/json;charset=UTF-8");
            return false;
        }
        return true;
    }

    /** 从请求 URI 提取设备段（context-path 由容器已剥离；无设备段返回 null=不绑定）。 */
    static String pathDeviceId(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher m = DEVICE_PATH.matcher(uri);
        return m.matches() ? m.group(1) : null;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
