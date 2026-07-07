package com.aicabinet.trade.config;

import com.aicabinet.common.constants.InternalApiConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

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
        if (provided == null || !internalApiProperties.key().equals(provided)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        return true;
    }
}
