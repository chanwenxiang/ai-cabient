package com.aicabinet.trade.auth;

import com.aicabinet.trade.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;

/**
 * 浏览器端会话 Cookie（HttpOnly + SameSite=Strict）。
 * 运营/商户与消费者分 Cookie，避免同域互盖导致后台误用消费者身份。
 * 小程序/App 不走 Cookie，仍使用 Bearer token。
 */
@Component
@Getter
@Setter
public class SessionCookieService {

    /** 消费者 H5 / 公开端会话 */
    public static final String CONSUMER_SESSION_COOKIE_NAME = "aicabinet_session";
    /** 运营后台 / 商户端会话 */
    public static final String ADMIN_SESSION_COOKIE_NAME = "aicabinet_admin_session";

    public enum Realm {
        CONSUMER,
        ADMIN
    }

    private final AuthProperties authProperties;

    public SessionCookieService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public boolean isCookieEnabled() {
        return authProperties.cookieEnabled();
    }

    public static boolean isAdminApiPath(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return false;
        }
        String path = requestUri;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        return path.startsWith("/api/v2/ops") || path.startsWith("/api/v2/merchant");
    }

    /** 按请求路径选择 Cookie：ops/merchant 只用运营 Cookie，避免消费者会话串入后台。 */
    public String resolveTokenForPath(HttpServletRequest request, String requestUri) {
        if (isAdminApiPath(requestUri)) {
            return resolveToken(request, Realm.ADMIN);
        }
        String consumer = resolveToken(request, Realm.CONSUMER);
        if (consumer != null) {
            return consumer;
        }
        // 字典等共享接口：仅有运营会话时也可带 Cookie 访问
        return resolveToken(request, Realm.ADMIN);
    }

    /** @deprecated 请用 {@link #resolveToken(HttpServletRequest, Realm)}；默认消费者 Cookie。 */
    @Deprecated
    public String resolveToken(HttpServletRequest request) {
        return resolveToken(request, Realm.CONSUMER);
    }

    public String resolveToken(HttpServletRequest request, Realm realm) {
        if (!authProperties.cookieEnabled() || realm == null) {
            return null;
        }
        String name = cookieName(realm);
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Cookie 刷新场景：解析当前请求应使用的会话。
     * 两套 Cookie 同时存在时，用 Referer 区分运营后台/商户端与消费者端。
     */
    public PresentedSession resolvePresentedSession(HttpServletRequest request) {
        String adminToken = resolveToken(request, Realm.ADMIN);
        String consumerToken = resolveToken(request, Realm.CONSUMER);
        if (adminToken != null && consumerToken == null) {
            return new PresentedSession(adminToken, Realm.ADMIN);
        }
        if (consumerToken != null && adminToken == null) {
            return new PresentedSession(consumerToken, Realm.CONSUMER);
        }
        if (adminToken == null) {
            return null;
        }
        String referer = request.getHeader("Referer");
        if (referer != null) {
            String lower = referer.toLowerCase();
            if (lower.contains("/admin") || lower.contains("merchant")) {
                return new PresentedSession(adminToken, Realm.ADMIN);
            }
        }
        return new PresentedSession(consumerToken, Realm.CONSUMER);
    }

    public record PresentedSession(String token, Realm realm) {}

    public void writeSessionCookie(HttpServletResponse response, String token, Realm realm) {
        if (!authProperties.cookieEnabled() || token == null || token.isBlank() || realm == null) {
            return;
        }
        int maxAge = (int) Math.min(Integer.MAX_VALUE, authProperties.expirationSeconds());
        response.addCookie(createCookie(cookieName(realm), token, maxAge));
    }

    /** @deprecated 请用带 Realm 的重载；默认写消费者 Cookie。 */
    @Deprecated
    public void writeSessionCookie(HttpServletResponse response, String token) {
        writeSessionCookie(response, token, Realm.CONSUMER);
    }

    public void clearSessionCookie(HttpServletResponse response, Realm realm) {
        if (realm == null) {
            return;
        }
        response.addCookie(createCookie(cookieName(realm), "", 0));
    }

    /** @deprecated 请用带 Realm 的重载；默认清消费者 Cookie。 */
    @Deprecated
    public void clearSessionCookie(HttpServletResponse response) {
        clearSessionCookie(response, Realm.CONSUMER);
    }

    private static String cookieName(Realm realm) {
        return realm == Realm.ADMIN ? ADMIN_SESSION_COOKIE_NAME : CONSUMER_SESSION_COOKIE_NAME;
    }

    private Cookie createCookie(String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(authProperties.cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }
}
