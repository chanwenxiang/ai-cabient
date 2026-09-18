package com.aicabinet.common.security;

import com.aicabinet.common.constants.InternalApiConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部 API 共享密钥校验（trade-service / device-service 共用）。
 * 可选来源 CIDR 白名单；未配置 CIDR 时仅校验密钥。
 */
@Component
public class InternalApiAuthInterceptor implements HandlerInterceptor {

    private final InternalApiProperties internalApiProperties;

    public InternalApiAuthInterceptor(InternalApiProperties internalApiProperties) {
        this.internalApiProperties = internalApiProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!internalApiProperties.isConfigured()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return false;
        }
        String provided = request.getHeader(InternalApiConstants.API_KEY_HEADER);
        if (provided == null || !constantTimeEquals(provided, internalApiProperties.key())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        if (internalApiProperties.hasCidrRestriction()
                && !CidrAllowlist.isAllowed(resolveClientIp(request), internalApiProperties.allowedCidrList())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return false;
        }
        return true;
    }

    /**
     * M08：来源 IP 解析顺序 —— 仅当直连地址落在可信代理网段内时，才依次采用
     * 第一个合法的 X-Forwarded-For 左值 → X-Real-IP；否则一律使用 getRemoteAddr()。
     * 未配置 trusted-proxy-cidrs（默认空）时行为与旧版完全一致（只用直连地址），
     * 因此「配了 allowed-cidrs 但没配 trusted-proxy」的直连场景不受影响。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (internalApiProperties.hasTrustedProxy()
                && CidrAllowlist.isAllowed(remoteAddr, internalApiProperties.trustedProxyCidrList())) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            String candidate = firstValidIp(forwardedFor);
            if (candidate != null) {
                return candidate;
            }
            candidate = validIp(request.getHeader("X-Real-IP"));
            if (candidate != null) {
                return candidate;
            }
        }
        return remoteAddr;
    }

    /** 取 X-Forwarded-For 第一个左值（trim 后须为合法 IP），非法则忽略整个头。 */
    private static String firstValidIp(String xff) {
        if (xff == null || xff.isBlank()) {
            return null;
        }
        return validIp(xff.split(",")[0]);
    }

    private static String validIp(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return CidrAllowlist.isIpv4Literal(v) ? v : null;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
