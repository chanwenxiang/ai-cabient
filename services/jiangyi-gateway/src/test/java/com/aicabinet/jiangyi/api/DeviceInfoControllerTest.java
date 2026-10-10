package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * V16 §4.2.4/§4.2.5/§4.2.13 设备面 deviceInfo 端点（PDF 原件核对后的主通道行为）。
 */
class DeviceInfoControllerTest {

    private static final String DEVICE_ID = "100000000001";
    private static final String IDENTIFIER = "CQYB1003";

    private TradeInternalClient tradeInternalClient;
    private DeviceInfoController controller;

    @BeforeEach
    void setUp() {
        tradeInternalClient = Mockito.mock(TradeInternalClient.class);
        controller = new DeviceInfoController(tradeInternalClient);
    }

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, DEVICE_ID);
        request.setAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER, IDENTIFIER);
        return request;
    }

    @Test
    void getDetailReturnsTokenIdentifier() {
        Map<String, Object> resp = controller.getDetail(authedRequest());
        assertEquals(200, resp.get("status"));
        assertEquals(IDENTIFIER, ((Map<?, ?>) resp.get("data")).get("identifier"));
    }

    @Test
    void downloadModelNotifyForwardsModelConfirmed() {
        Map<String, Object> resp = controller.downloadModelNotify(
                authedRequest(), new DeviceInfoController.DownloadModelNotifyRequest(IDENTIFIER, "model.rknn"));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).modelConfirmed(DEVICE_ID, "model.rknn");
    }

    @Test
    void downloadModelNotifyWithIdentifierMismatchStillUsesTokenDevice() {
        // 铁律：固件不可信——body.identifier 与 token 不一致时以 token 解出为准，照转不误
        controller.downloadModelNotify(authedRequest(),
                new DeviceInfoController.DownloadModelNotifyRequest("FAKE", "model.rknn"));
        verify(tradeInternalClient).modelConfirmed(eq(DEVICE_ID), eq("model.rknn"));
    }

    @Test
    void downloadModelNotifyWithoutModelNameLogsOnly() {
        // §4.3.2.9「下发时没有 modelName，上报时就为空」：不转发，响应仍 200 防设备重试风暴
        Map<String, Object> resp = controller.downloadModelNotify(authedRequest(),
                new DeviceInfoController.DownloadModelNotifyRequest(IDENTIFIER, null));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient, never()).modelConfirmed(anyString(), anyString());
    }

    @Test
    void downloadModelNotifyTradeFailureStillReturns200() {
        // 回执转发失败不能让设备侧看到 5xx（否则固件可能重试/离线）——与 WS 分支同语义
        Mockito.doThrow(new IllegalStateException("trade down"))
                .when(tradeInternalClient).modelConfirmed(anyString(), anyString());
        Map<String, Object> resp = controller.downloadModelNotify(authedRequest(),
                new DeviceInfoController.DownloadModelNotifyRequest(IDENTIFIER, "model.rknn"));
        assertEquals(200, resp.get("status"));
    }

    @Test
    void getArtificialCheckDefaultsToFalse() {
        Map<String, Object> resp = controller.getArtificialCheck(authedRequest());
        assertEquals(200, resp.get("status"));
        assertFalse((Boolean) resp.get("data"));
    }
}
