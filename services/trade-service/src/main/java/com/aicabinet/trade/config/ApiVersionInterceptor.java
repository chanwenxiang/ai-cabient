package com.aicabinet.trade.config;

import com.aicabinet.common.api.ApiVersions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 公开 API 版本门禁：标注当前版本响应头；未支持主版本直接 410。
 */
@Component
public class ApiVersionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        var majorOpt = ApiVersions.parsePublicApiMajor(path);
        if (majorOpt.isEmpty()) {
            return true;
        }
        int major = majorOpt.getAsInt();
        if (!ApiVersions.isSupported(major)) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, ApiVersions.unsupportedMessage(major));
        }
        response.setHeader(ApiVersions.RESPONSE_HEADER, ApiVersions.CURRENT);

        String requested = request.getHeader(ApiVersions.REQUEST_HEADER);
        if (requested != null && !requested.isBlank()) {
            String normalized = requested.trim().toLowerCase().replace("version=", "");
            if (normalized.startsWith("v")) {
                normalized = normalized.substring(1);
            }
            if (!String.valueOf(ApiVersions.CURRENT_MAJOR).equals(normalized)
                    && !ApiVersions.CURRENT.equalsIgnoreCase(requested.trim())) {
                log.warn("X-Api-Version={} mismatches path major={} uri={}",
                        requested, major, path);
            }
        }
        return true;
    }
}
