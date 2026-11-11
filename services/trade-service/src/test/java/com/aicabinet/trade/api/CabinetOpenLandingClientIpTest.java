package com.aicabinet.trade.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R14-G1：匿名限流取 IP 信任序倒置回归钉测。
 * 本仓 nginx 为追加型 XFF（$proxy_add_x_forwarded_for）+ 恒定覆写 X-Real-IP
 * ($remote_addr)。旧实现取 XFF 首跳 ⇒ 攻击者轮换假 XFF 即绕过 L04 防探测限流。
 * 修复后信任序：X-Real-IP（不可伪造）→ XFF 首跳（仅直连回退）→ remoteAddr。
 */
class CabinetOpenLandingClientIpTest {

    private static String ip(MockHttpServletRequest req) {
        return CabinetOpenLandingController.resolveClientIp(req);
    }

    @Test
    void realIpWinsOverForgedXffFirstHop() {
        // 经 nginx 拓扑：攻击者伪造 XFF 首跳 + nginx 追加真实来源 + 覆写 X-Real-IP
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        req.addHeader("X-Real-IP", "203.0.113.7");
        req.setRemoteAddr("10.0.0.2");
        assertEquals("203.0.113.7", ip(req), "X-Real-IP 优先，伪造 XFF 首跳不得成为限流键");
    }

    @Test
    void xffFirstHopUsedOnlyWhenRealIpAbsent() {
        // 直连回退口径保持：未过反代（无 X-Real-IP）时沿用 XFF 首跳
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "198.51.100.9 , 203.0.113.7");
        req.setRemoteAddr("10.0.0.2");
        assertEquals("198.51.100.9", ip(req));
    }

    @Test
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.0.2.11");
        assertEquals("192.0.2.11", ip(req));
    }

    @Test
    void blankRealIpDoesNotShadowXff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", "  ");
        req.addHeader("X-Forwarded-For", "198.51.100.9");
        req.setRemoteAddr("10.0.0.2");
        assertEquals("198.51.100.9", ip(req));
    }
}
