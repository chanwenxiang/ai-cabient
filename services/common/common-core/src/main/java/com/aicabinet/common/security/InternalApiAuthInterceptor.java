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
 * 仅用于服务间 /internal/** 调用，不对外暴露。
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
        return true;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
