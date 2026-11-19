package com.aicabinet.trade.service;

import com.aicabinet.trade.api.CabinetOpenLandingController;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.wechat.MiniAppTarget;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceQrServiceTest {

    @Test
    void buildOpenUrl_usesPublicHostAndDeviceId() {
        QrProperties props = new QrProperties("https://open.example.com/", "", "", "", "release");
        DeviceQrService service = new DeviceQrService(null, props, null);
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

    /**
     * 生产未部署消费者 H5 时必须能被识别出来，否则落地页会 302 到
     * 「同主机 + :3002」这个本机开发端口，支付宝用户看到「无法打开网页」
     * （2026-11-19 生产实测）。空值/auto 一律视为未配置。
     */
    @Test
    void hasConsumerH5_falseWhenUnsetOrAuto() {
        assertFalse(new QrProperties("https://x", null, "", "", "release").hasConsumerH5());
        assertFalse(new QrProperties("https://x", "", "", "", "release").hasConsumerH5());
        assertFalse(new QrProperties("https://x", "auto", "", "", "release").hasConsumerH5());
    }

    @Test
    void hasConsumerH5_trueWhenExplicitBaseConfigured() {
        assertTrue(
                new QrProperties("https://x", "https://cabinet.easygovm.com/h5/", "", "", "release")
                        .hasConsumerH5());
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

    // ── 柜门贴码：小程序码（2026-11-19） ───────────────────────────────────────
    // 缺陷语义：柜门码走普通链接二维码时依赖公众平台校验文件，文件一丢（容器重建）
    // 码就扫不动，而后台照样给「下载成功」——静默失效。改小程序码后，微信侧失败
    // **必须**让调用方看到（empty → 控制器 503），不得悄悄退回普通二维码。

    private static final String DEVICE = "EG858564255060";

    private static DeviceQrService serviceWith(WeChatMiniAppClient client) {
        DeviceInfoMapper mapper = mock(DeviceInfoMapper.class);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEVICE);
        when(mapper.selectById(DEVICE)).thenReturn(device);
        // wechatMpPath/wechatMpEnv 留空 ⇒ 走默认 pages/index/index + release
        QrProperties props = new QrProperties("https://cabinet.example.com", "", "", "", "");
        return new DeviceQrService(mapper, props, client);
    }

    /** 出码参数必须是「柜机号当 scene + 消费者端 + 默认落地页/版本」。 */
    @Test
    void wxacodeFor_passesDeviceIdAsSceneToConsumerMiniApp() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        WeChatMiniAppClient client = mock(WeChatMiniAppClient.class);
        when(client.getWxaCodeUnlimited(DEVICE, "pages/index/index", "release", MiniAppTarget.CONSUMER))
                .thenReturn(Optional.of(jpeg));

        assertArrayEquals(jpeg, serviceWith(client).wxacodeFor(DEVICE).orElseThrow());
    }

    /**
     * 微信侧失败 ⇒ empty，**不降级**。
     *
     * <p>若有人把这里改回 `pngFor(...)` 兜底，本用例立刻红——这正是要钉住的回归点。
     */
    @Test
    void wxacodeFor_returnsEmptyOnWeChatFailure_noSilentFallback() {
        WeChatMiniAppClient client = mock(WeChatMiniAppClient.class);
        when(client.getWxaCodeUnlimited(DEVICE, "pages/index/index", "release", MiniAppTarget.CONSUMER))
                .thenReturn(Optional.empty());

        assertTrue(serviceWith(client).wxacodeFor(DEVICE).isEmpty());
    }

    /** 柜机不存在 ⇒ 404（不能给出「别人的柜机」的码）。 */
    @Test
    void wxacodeFor_unknownDeviceThrows404() {
        DeviceInfoMapper mapper = mock(DeviceInfoMapper.class);
        when(mapper.selectById(DEVICE)).thenReturn(null);
        QrProperties props = new QrProperties("https://cabinet.example.com", "", "", "", "");
        DeviceQrService service = new DeviceQrService(mapper, props, mock(WeChatMiniAppClient.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.wxacodeFor(DEVICE));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
