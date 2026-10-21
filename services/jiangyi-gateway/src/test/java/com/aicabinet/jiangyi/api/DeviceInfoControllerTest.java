package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void getDetailReturnsMeasuredHardwareTopologyNotNulls() {
        // 2026-10-19 log2/log3 实锤：固件把 doorNum/locks 当门锁驱动（云侧回 doorNum:2 的那次
        // 开机 25ms 内即写 gpio4 报 ENOENT，其余 doorNum:1 的开机零异常），把 usbNum* 当摄像头
        // 总线分配。⇒ 拓扑字段必须是数字，回 null 属未定义行为。
        // 0/null 语义分开：云侧对「通道不存在」回 0（usbNumRu/Rb），只对「未维护元数据」回 null。
        Map<String, Object> resp = controller.getDetail(authedRequest());
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        assertEquals("yes", data.get("isBind"));
        assertEquals("end", data.get("recognitionType"));
        assertEquals(1, data.get("doorNum"));
        assertEquals(List.of(1), data.get("locks"));
        assertEquals(1, data.get("usbNumLu"));
        assertEquals(3, data.get("usbNumLb"));
        assertEquals(0, data.get("usbNumRu"));
        assertEquals(0, data.get("usbNumRb"));
        assertEquals(2, data.get("modelNum"));
        assertNull(data.get("huodaoNum"));
        assertNull(data.get("industrialControlType"));
        assertNull(data.get("mqttClientId"));
        assertEquals(IDENTIFIER, data.get("identifier"));
        // 云侧 id 是 JSON 数字（10720）；纯数字的 deviceId 必须同型下发，非数字才允许回字符串
        assertEquals(100000000001L, data.get("id"));
        // 键集与云侧实测响应逐一对齐（19 键）：多一键是我方发明，少一键是固件可能读不到预期字段
        assertEquals(java.util.Set.of("id", "identifier", "isBind", "recognitionType", "doorNum",
                        "locks", "usbNumLu", "usbNumLb", "usbNumRu", "usbNumRb", "modelNum", "huodaoNum",
                        "industrialControlType", "mqttClientId", "mqttTopicPrefix", "lastOnlineTime",
                        "mqttStatus", "lastHeartbeatTime", "mqttQos"), data.keySet());
    }

    @Test
    void getDetailKeepsNonNumericDeviceIdAsString() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, "EG943322621391");
        request.setAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER, IDENTIFIER);
        Map<?, ?> data = (Map<?, ?>) controller.getDetail(request).get("data");
        assertEquals("EG943322621391", data.get("id"));
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

    /**
     * 文档只列 downloadModelNotify（§4.2.5），真机固件另外打三条分档回执（log4 行
     * 24231 base / 30621 big / 17847 third，body 同形）。四条必须同映射：漏一条 ⇒
     * 该档下发拿不到 CONFIRMED，watchdog 把已成功的应用误判成超时 FAILED。
     */
    @Test
    void downloadModelNotifyMapsAllFourFirmwarePaths() throws Exception {
        PostMapping mapping = DeviceInfoController.class
                .getMethod("downloadModelNotify", HttpServletRequest.class,
                        DeviceInfoController.DownloadModelNotifyRequest.class)
                .getAnnotation(PostMapping.class);
        assertEquals(Set.of(
                        "/jiangyi/api/deviceInfo/downloadModelNotify",
                        "/jiangyi/api/deviceInfo/downloadBaseModelNotify",
                        "/jiangyi/api/deviceInfo/downloadBigModelNotify",
                        "/jiangyi/api/deviceInfo/downloadThirdModelNotify"),
                Set.of(mapping.value()));
    }

    @Test
    void variantModelNotifyForwardsSameAsPrimary() {
        // 三档语义未向厂商核实 ⇒ 不区分处理；错档回执由 trade confirmModel 的
        // modelName 匹配（不匹配 409）挡住，不会伪造 CONFIRMED。
        MockHttpServletRequest request = authedRequest();
        request.setRequestURI("/jiangyi/api/deviceInfo/downloadBigModelNotify");
        Map<String, Object> resp = controller.downloadModelNotify(request,
                new DeviceInfoController.DownloadModelNotifyRequest(IDENTIFIER, "best_800rk3588_0310_800senpt.rknn"));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).modelConfirmed(DEVICE_ID, "best_800rk3588_0310_800senpt.rknn");
    }

    @Test
    void getArtificialCheckDefaultsToFalse() {
        Map<String, Object> resp = controller.getArtificialCheck(authedRequest());
        assertEquals(200, resp.get("status"));
        assertFalse((Boolean) resp.get("data"));
    }

    @Test
    void receiveCurrentTxtUrlCopiesCloudBusinessFailure() {
        // 云侧实测恒 {"status":666,"msg":"当前模型text文件地址不存在","data":null}（log3 行 291/640）。
        // 回 200 = 固件以为有 txt 地址可取，会去下载一个不存在的文件。
        // data 必须显式为 null（Map.of 会 NPE，测试同时锁住这条构造）。
        Map<String, Object> resp = controller.receiveCurrentTxtUrl(
                authedRequest(), Map.of("identifier", IDENTIFIER));
        assertEquals(666, resp.get("status"));
        assertEquals("当前模型text文件地址不存在", resp.get("msg"));
        assertTrue(resp.containsKey("data"));
        assertNull(resp.get("data"));
    }
}
