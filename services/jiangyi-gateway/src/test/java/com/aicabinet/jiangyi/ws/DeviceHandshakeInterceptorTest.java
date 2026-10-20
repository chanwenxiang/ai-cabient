package com.aicabinet.jiangyi.ws;

import com.aicabinet.jiangyi.auth.DeviceTokenService;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.JiangyiDeviceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备 WS 握手拦截单测（2026-10-20）。
 * <p>核心场景：固件 WS 拨号 URL 拼接段存在 identifier / SN 两种未定谳口径
 * （V16 文档 vs smali 读数），拦截器对两者都放行但跨机失配仍 fail-closed。
 * 另覆盖归一化：无论 path 形态，ATTR_IDENTIFIER 必须落 canonical identifier。</p>
 */
class DeviceHandshakeInterceptorTest {

    private static final String SECRET_32 = "jiangyi-test-secret-0123456789abcdef!";
    private static final String DEVICE_ID = "EG943322621391";
    private static final String SN = "YPKJ1JYKJ00000000000250";
    private static final String IDENTIFIER = "CQYB10720";

    private DeviceTokenService tokenService;
    private DeviceHandshakeInterceptor interceptor;
    private MockHttpServletRequest servletRequest;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        tokenService = new DeviceTokenService(SECRET_32, 3600);
        TradeInternalClient tradeClient = org.mockito.Mockito.mock(TradeInternalClient.class);
        org.mockito.Mockito.when(tradeClient.device(DEVICE_ID))
                .thenReturn(Optional.of(device()));
        interceptor = new DeviceHandshakeInterceptor(tokenService, tradeClient);
        servletRequest = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private JiangyiDeviceView device() {
        return new JiangyiDeviceView(DEVICE_ID, SN, IDENTIFIER,
                "model76", null, "BOUND", 0L, Instant.now(), null, true);
    }

    private String token() {
        return tokenService.issue(device());
    }

    private Map<String, Object> handshake(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        servletRequest.setRequestURI(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
        if (q >= 0) {
            servletRequest.setQueryString(pathAndQuery.substring(q + 1));
            servletRequest.setParameter("token", pathAndQuery.substring(q + "token=".length() + 1));
        }
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(
                new org.springframework.http.server.ServletServerHttpRequest(servletRequest),
                new org.springframework.http.server.ServletServerHttpResponse(response),
                null, attributes);
        assertTrue(accepted == (response.getStatus() < 400),
                "accept 与状态码必须一致");
        return attributes;
    }

    @Test
    void acceptsIdentifierFormAndCanonicalizes() {
        Map<String, Object> attrs = handshake("/websocket/device/" + IDENTIFIER + "?token=" + token());
        assertEquals(IDENTIFIER, attrs.get(DeviceHandshakeInterceptor.ATTR_IDENTIFIER));
        assertEquals(DEVICE_ID, attrs.get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID));
    }

    @Test
    void acceptsSnFormAndCanonicalizesToIdentifier() {
        // smali 口径：设备拼 SN 而非 identifier——必须放行且归一化为 canonical identifier
        Map<String, Object> attrs = handshake("/websocket/device/" + SN + "?token=" + token());
        assertEquals(IDENTIFIER, attrs.get(DeviceHandshakeInterceptor.ATTR_IDENTIFIER));
        assertEquals(DEVICE_ID, attrs.get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID));
    }

    @Test
    void rejectsForeignChannelEvenWithValidToken() {
        // A 机 token 连 B 机通道：path 既非 token identifier 也非 token sn ⇒ 仍 fail-closed
        Map<String, Object> attrs = handshake("/websocket/device/CQYB99999?token=" + token());
        assertTrue(attrs.isEmpty());
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsMissingToken() {
        Map<String, Object> attrs = handshake("/websocket/device/" + IDENTIFIER);
        assertTrue(attrs.isEmpty());
        assertEquals(401, response.getStatus());
    }
}
