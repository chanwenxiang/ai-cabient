package com.aicabinet.trade.auth;

import com.aicabinet.trade.support.ApiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private static final String BEARER = "Bearer ";


    public static final String ATTR_USER_ID = "userId";

    private final JwtService jwtService;
    private final SessionCookieService sessionCookieService;

    public AuthInterceptor(JwtService jwtService, SessionCookieService sessionCookieService) {
        this.jwtService = jwtService;
        this.sessionCookieService = sessionCookieService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        boolean viaCookie = false;
        if (auth == null || !auth.startsWith(BEARER)) {
            String cookieToken = sessionCookieService.resolveToken(request);
            if (cookieToken != null) {
                auth = BEARER + cookieToken;
                viaCookie = true;
            } else if ("GET".equalsIgnoreCase(request.getMethod())) {
                // 仅 GET 允许 query access_token（小程序下载争议证据等 <a>/<img> 场景），
                // 避免写操作/敏感请求把 token 泄漏到访问日志或浏览器历史。
                String queryToken = request.getParameter("access_token");
                if (queryToken != null && !queryToken.isBlank()) {
                    auth = BEARER + queryToken.trim();
                }
            }
        }
        if (auth == null || !auth.startsWith(BEARER)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.MISSING_TOKEN);
        }
        // CSRF 双保险：Cookie 会话的写请求必须带同源标记头（小程序走 Bearer，不受影响）。
        if (viaCookie && isStateChanging(request.getMethod()) && !isXhr(request)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.CSRF_HEADER_REQUIRED);
        }
        try {
            Long userId = jwtService.validateAndGetUserId(auth.substring(7));
            request.setAttribute(ATTR_USER_ID, userId);
            return true;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        }
    }

    private static boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static boolean isXhr(HttpServletRequest request) {
        String value = request.getHeader("X-Requested-With");
        return value != null && value.toLowerCase().contains("xmlhttprequest");
    }
}
