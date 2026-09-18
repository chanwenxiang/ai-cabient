package com.aicabinet.common.security;

import com.aicabinet.common.constants.InternalApiConstants;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M08：InternalApi 来源 CIDR 校验 —— 仅当直连地址来自可信代理网段时才解析
 * X-Forwarded-For / X-Real-IP；默认（未配 trusted-proxy）保持旧的直连地址语义。
 */
class InternalApiAuthInterceptorTest {

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static InternalApiProperties props(String allowedCidrs, String trustedProxyCidrs) {
        return new InternalApiProperties("test-key", allowedCidrs, trustedProxyCidrs);
    }

    private boolean preHandle(InternalApiProperties properties, MockHttpServletRequest request) {
        return new InternalApiAuthInterceptor(properties).preHandle(request, response, new Object());
    }

    private static MockHttpServletRequest request(String remoteAddr, String xff, String xRealIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader(InternalApiConstants.API_KEY_HEADER, "test-key");
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        if (xRealIp != null) {
            request.addHeader("X-Real-IP", xRealIp);
        }
        return request;
    }

    @Test
    void withoutCidrRestrictionHeaderOnlyCheckApplies() {
        assertTrue(preHandle(props(null, null), request("8.8.8.8", null, null)));
        assertTrue(preHandle(props("", ""), request("10.1.2.3", "anything", null)));
    }

    @Test
    void wrongKeyRejected() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.1.2.3");
        req.addHeader(InternalApiConstants.API_KEY_HEADER, "bad-key");
        assertFalse(preHandle(props("10.0.0.0/8", null), req));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }

    @Test
    void directClientWithoutTrustedProxyUsesRemoteAddr_only() {
        // 未配 trusted-proxy：XFF 伪造为白名单 IP 也必须被忽略，按直连地址判定
        InternalApiProperties props = props("10.0.0.0/8", "");
        assertFalse(preHandle(props, request("8.8.8.8", "10.1.2.3", "10.1.2.3")),
                "spoofed XFF must not grant access");
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
        assertTrue(preHandle(props, request("10.1.2.3", "8.8.8.8", null)),
                "direct client inside CIDR stays allowed");
    }

    @Test
    void trustedProxyXffLeftValueUsedWhenRemoteIsTrusted() {
        InternalApiProperties props = props("192.168.0.0/16", "172.16.0.0/12");
        // 网关 172.16.0.9 转发的真实客户端 192.168.1.5
        assertTrue(preHandle(props, request("172.16.0.9", "192.168.1.5, 10.0.0.1", null)));
        // 真实客户端不在白名单 → 拒绝
        assertFalse(preHandle(props, request("172.16.0.9", "8.8.8.8", null)));
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
    }

    @Test
    void untrustedRemoteIgnoresForwardedHeaders() {
        InternalApiProperties props = props("10.0.0.0/8", "172.16.0.0/12");
        // 直连地址不在可信代理网段：即使伪造 XFF 也按直连地址（不在白名单）拒绝
        assertFalse(preHandle(props, request("8.8.8.8", "10.1.2.3", "10.1.2.3")));
    }

    @Test
    void invalidXffFallsBackToXRealIpThenRemoteAddr() {
        InternalApiProperties props = props("192.168.0.0/16", "172.16.0.0/12");
        // XFF 左值非法 → 回退 X-Real-IP
        assertTrue(preHandle(props, request("172.16.0.9", "not-an-ip", "192.168.7.7")));
        // 两者都非法/缺失 → 回退直连地址（网关自身网段不在白名单则拒绝）
        assertFalse(preHandle(props, request("172.16.0.9", "evil, 192.168.1.1", null)));
        // 直连地址本身在白名单（如本机直连）→ 放行
        assertTrue(preHandle(props, request("192.168.1.1", null, null)));
    }
}
