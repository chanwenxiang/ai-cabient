package com.aicabinet.trade.service;

import com.aicabinet.trade.api.CabinetOpenLandingController;
import com.aicabinet.trade.config.QrProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceQrServiceTest {

    @Test
    void buildOpenUrl_usesPublicHostAndDeviceId() {
        QrProperties props = new QrProperties("https://open.example.com/", "", "", "", "release");
        DeviceQrService service = new DeviceQrService(null, props);
        assertEquals("https://open.example.com/o/CAB-001", service.buildOpenUrl("CAB-001"));
    }

    @Test
    void normalizeDeviceId_rejectsBlank() {
        assertThrows(Exception.class, () -> DeviceQrService.normalizeDeviceId(" "));
    }

    /** deviceId 大小写敏感（创建入口不转写）：小写必须原样保留，不得被转写成不存在的 ID。 */
    @Test
    void normalizeDeviceId_preservesCase() {
        assertEquals("jiangyi-test-001", DeviceQrService.normalizeDeviceId(" jiangyi-test-001 "));
        assertEquals("CAB-001", DeviceQrService.normalizeDeviceId("CAB-001"));
        assertThrows(Exception.class, () -> DeviceQrService.normalizeDeviceId("柜机#1"));
    }

    @Test
    void consumerH5Base_autoUsesPort3002OnSameHost() {
        QrProperties props = new QrProperties("http://192.168.1.8", "auto", "", "", "release");
        assertEquals("http://192.168.1.8:3002/", props.normalizedConsumerH5Base());
    }

    @Test
    void resolveChannel_byUserAgent() {
        assertEquals("ALIPAY", CabinetOpenLandingController.resolveChannel("Mozilla/5.0 AlipayClient"));
        assertEquals("WECHAT", CabinetOpenLandingController.resolveChannel("MicroMessenger"));
        assertEquals("OTHER", CabinetOpenLandingController.resolveChannel("Chrome"));
    }

    @Test
    void wechatEnvVersion_normalizes() {
        assertEquals("trial", new QrProperties("http://x", "", "", "", "TRIAL").wechatEnvVersion());
        assertEquals("release", new QrProperties("http://x", "", "", "", "weird").wechatEnvVersion());
    }
}
