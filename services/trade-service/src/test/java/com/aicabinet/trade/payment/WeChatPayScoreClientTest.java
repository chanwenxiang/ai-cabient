package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信支付分授权链路字段/路径契约（CB-064「2026-10-24 字段级复核」）：v3 预授权没有
 * {@code out_request_no}，查询/解约均按 {@code authorization-code}。
 */
@ExtendWith(MockitoExtension.class)
class WeChatPayScoreClientTest {

    @Mock WeChatPayV3Client v3Client;
    @Mock WeChatPayProperties weChatPayProperties;
    @Mock PayScoreProperties payScoreProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WeChatPayScoreClient client;

    @BeforeEach
    void setUp() {
        client = new WeChatPayScoreClient(v3Client, weChatPayProperties, payScoreProperties);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePostedBody(String expectedPath) {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(v3Client).post(path.capture(), body.capture());
        assertEquals(expectedPath, path.getValue());
        return (Map<String, Object>) body.getValue();
    }

    @Test
    void createPermission_sendsContractFieldsOnly() {
        when(weChatPayProperties.appId()).thenReturn("wx-app");
        when(payScoreProperties.serviceId()).thenReturn("500001");
        when(payScoreProperties.permissionNotifyUrl()).thenReturn("");
        when(v3Client.post(anyString(), any())).thenReturn(objectMapper.nullNode());

        client.createPermission("PSU-ABC123");

        Map<String, Object> body = capturePostedBody("/v3/payscore/permissions");
        assertEquals("wx-app", body.get("appid"));
        assertEquals("500001", body.get("service_id"));
        assertEquals("PSU-ABC123", body.get("authorization_code"));
        assertFalse(body.containsKey("out_request_no"));
        assertFalse(body.containsKey("openid"));
        assertFalse(body.containsKey("notify_url"), "空回调地址不得发 null");
    }

    @Test
    void createPermission_includesNotifyUrlWhenConfigured() {
        when(weChatPayProperties.appId()).thenReturn("wx-app");
        when(payScoreProperties.serviceId()).thenReturn("500001");
        when(payScoreProperties.permissionNotifyUrl()).thenReturn("https://example.com/notify");
        when(v3Client.post(anyString(), any())).thenReturn(objectMapper.nullNode());

        client.createPermission("PSU-ABC123");

        assertEquals("https://example.com/notify",
                capturePostedBody("/v3/payscore/permissions").get("notify_url"));
    }

    @Test
    void queryPermission_usesAuthorizationCodePath() {
        when(payScoreProperties.serviceId()).thenReturn("500001");
        when(v3Client.get(anyString())).thenReturn(objectMapper.nullNode());

        client.queryPermission("PSU-ABC123");

        verify(v3Client).get("/v3/payscore/permissions/authorization-code/PSU-ABC123?service_id=500001");
    }

    @Test
    void terminatePermission_postsTerminateWithServiceIdAndReason() {
        when(payScoreProperties.serviceId()).thenReturn("500001");
        when(v3Client.post(anyString(), any())).thenReturn(objectMapper.nullNode());

        client.terminatePermission("PSU-ABC123", "用户申请解约");

        Map<String, Object> body = capturePostedBody(
                "/v3/payscore/permissions/authorization-code/PSU-ABC123/terminate");
        assertEquals("500001", body.get("service_id"));
        assertEquals("用户申请解约", body.get("reason"));
    }

    @Test
    void terminatePermission_defaultsReasonWhenBlank() {
        when(payScoreProperties.serviceId()).thenReturn("500001");
        when(v3Client.post(anyString(), any())).thenReturn(objectMapper.nullNode());

        client.terminatePermission("PSU-ABC123", " ");

        assertEquals("用户申请解约",
                capturePostedBody("/v3/payscore/permissions/authorization-code/PSU-ABC123/terminate")
                        .get("reason"));
    }
}
