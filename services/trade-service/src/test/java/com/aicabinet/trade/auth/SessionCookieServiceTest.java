package com.aicabinet.trade.auth;

import com.aicabinet.trade.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCookieServiceTest {

    private SessionCookieService service;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties(
                "unit-test-jwt-secret-key-32bytes!!",
                1800,
                true,
                false,
                5,
                15,
                new AuthProperties.SmsProperties("123456", 300, null, "webhook",
                        null, null, null, null, null),
                AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS);
        service = new SessionCookieService(props);
    }

    @Test
    void writeAndResolve_adminAndConsumerDoNotOverwriteEachOther() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writeSessionCookie(response, "admin-token", SessionCookieService.Realm.ADMIN);
        service.writeSessionCookie(response, "consumer-token", SessionCookieService.Realm.CONSUMER);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(response.getCookies());

        assertEquals("admin-token", service.resolveToken(request, SessionCookieService.Realm.ADMIN));
        assertEquals("consumer-token", service.resolveToken(request, SessionCookieService.Realm.CONSUMER));
    }

    /**
     * 回归（2026-10-22 真机实锤「后台一会就掉线」）：会话 Cookie 寿命必须覆盖
     * 「JWT 有效期 + refresh 宽限期」——否则闲置超过有效期后浏览器先删 Cookie，
     * 用户回来时 refresh 无凭据可用，只能重登（宽限窗口形同虚设）。
     */
    @Test
    void writeSessionCookie_maxAgeCoversRefreshGraceWindow() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writeSessionCookie(response, "t", SessionCookieService.Realm.ADMIN);

        int expected = 1800 + (int) AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS;
        assertEquals(expected, response.getCookie(SessionCookieService.ADMIN_SESSION_COOKIE_NAME).getMaxAge(),
                "Cookie Max-Age 必须 >= 有效期 + refresh 宽限期，闲置回归用户才能静默续期");
    }

    @Test
    void resolveForPath_opsUsesAdminCookieEvenWhenConsumerCookiePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(SessionCookieService.ADMIN_SESSION_COOKIE_NAME, "admin-token"),
                new Cookie(SessionCookieService.CONSUMER_SESSION_COOKIE_NAME, "consumer-token"));

        assertEquals("admin-token",
                service.resolveTokenForPath(request, "/api/v2/ops/admin/devices"));
        assertEquals("consumer-token",
                service.resolveTokenForPath(request, "/api/v2/account/me"));
        assertEquals("admin-token",
                service.resolveTokenForPath(request, "/api/v2/merchant/dashboard"));
    }

    @Test
    void clearAdmin_doesNotClearConsumer() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.writeSessionCookie(response, "admin-token", SessionCookieService.Realm.ADMIN);
        service.writeSessionCookie(response, "consumer-token", SessionCookieService.Realm.CONSUMER);

        MockHttpServletResponse clearResponse = new MockHttpServletResponse();
        service.clearSessionCookie(clearResponse, SessionCookieService.Realm.ADMIN);

        boolean clearedAdmin = false;
        for (Cookie cookie : clearResponse.getCookies()) {
            if (SessionCookieService.ADMIN_SESSION_COOKIE_NAME.equals(cookie.getName())) {
                assertTrue(cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty());
                clearedAdmin = true;
            }
            if (SessionCookieService.CONSUMER_SESSION_COOKIE_NAME.equals(cookie.getName())) {
                throw new AssertionError("consumer cookie must not be cleared on admin logout");
            }
        }
        assertTrue(clearedAdmin);
    }

    @Test
    void resolvePresentedSession_bothCookies_prefersAdminWhenRefererIsAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(SessionCookieService.ADMIN_SESSION_COOKIE_NAME, "admin-token"),
                new Cookie(SessionCookieService.CONSUMER_SESSION_COOKIE_NAME, "consumer-token"));
        request.addHeader("Referer", "http://127.0.0.1/admin/devices");

        SessionCookieService.PresentedSession presented = service.resolvePresentedSession(request);
        assertEquals("admin-token", presented.token());
        assertEquals(SessionCookieService.Realm.ADMIN, presented.realm());
    }

    @Test
    void resolvePresentedSession_bothCookies_prefersConsumerWithoutAdminReferer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(SessionCookieService.ADMIN_SESSION_COOKIE_NAME, "admin-token"),
                new Cookie(SessionCookieService.CONSUMER_SESSION_COOKIE_NAME, "consumer-token"));
        request.addHeader("Referer", "http://127.0.0.1:3002/");

        SessionCookieService.PresentedSession presented = service.resolvePresentedSession(request);
        assertEquals("consumer-token", presented.token());
        assertEquals(SessionCookieService.Realm.CONSUMER, presented.realm());
    }
}
