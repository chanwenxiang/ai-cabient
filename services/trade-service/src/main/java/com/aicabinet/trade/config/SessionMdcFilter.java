package com.aicabinet.trade.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 将 sessionId 写入 MDC，便于全链路日志关联（设计文档可观测性建议）。
 */
@Component
@Order(1)
public class SessionMdcFilter extends OncePerRequestFilter {

    public static final String HEADER_SESSION_ID = "X-Session-Id";
    public static final String MDC_SESSION_ID = "sessionId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String sessionId = request.getHeader(HEADER_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = request.getParameter(MDC_SESSION_ID);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(MDC_SESSION_ID, sessionId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_SESSION_ID);
        }
    }
}
