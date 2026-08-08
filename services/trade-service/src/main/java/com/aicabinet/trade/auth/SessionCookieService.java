package com.aicabinet.trade.auth;

import com.aicabinet.trade.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * 运营控制台等浏览器端会话 Cookie（HttpOnly + SameSite=Strict）。
 * 小程序/App 不走 Cookie，仍使用 Bearer token；两者互不干扰。
 */
@Component
public class SessionCookieService {

    public static final String SESSION_COOKIE_NAME = "aicabinet_session";

    private final AuthProperties authProperties;

    public SessionCookieService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public boolean isCookieEnabled() {
        return authProperties.cookieEnabled();
    }

    /** 从 HttpOnly Cookie 中取会话 token（仅当 cookie 认证开启时）。 */
    public String resolveToken(HttpServletRequest request) {
        if (!authProperties.cookieEnabled()) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void writeSessionCookie(HttpServletResponse response, String token) {
        if (!authProperties.cookieEnabled() || token == null || token.isBlank()) {
            return;
        }
        int maxAge = (int) Math.min(Integer.MAX_VALUE, authProperties.expirationSeconds());
        response.addCookie(createCookie(token, maxAge));
    }

    public void clearSessionCookie(HttpServletResponse response) {
        response.addCookie(createCookie("", 0));
    }

    private Cookie createCookie(String value, int maxAge) {
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(authProperties.cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }
}
