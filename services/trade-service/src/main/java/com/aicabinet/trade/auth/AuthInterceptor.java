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
        if (auth == null || !auth.startsWith("Bearer ")) {
            String cookieToken = sessionCookieService.resolveToken(request);
            if (cookieToken != null) {
                auth = "Bearer " + cookieToken;
            } else if ("GET".equalsIgnoreCase(request.getMethod())) {
                // 仅 GET 允许 query access_token（小程序下载争议证据等 <a>/<img> 场景），
                // 避免写操作/敏感请求把 token 泄漏到访问日志或浏览器历史。
                String queryToken = request.getParameter("access_token");
                if (queryToken != null && !queryToken.isBlank()) {
                    auth = "Bearer " + queryToken.trim();
                }
            }
        }
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.MISSING_TOKEN);
        }
        try {
            Long userId = jwtService.validateAndGetUserId(auth.substring(7));
            request.setAttribute(ATTR_USER_ID, userId);
            return true;
        } catch (JwtService.InvalidSessionTokenException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ApiMessages.INVALID_TOKEN);
        }
    }
}
