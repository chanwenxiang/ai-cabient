package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OtaRelease;
import com.aicabinet.trade.storage.MinioVideoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H20：OTA 白名单是排他语义——配置了非空白名单时，gray=100 也不能绕过白名单。 */
class OtaCdnServiceTest {

    private final MinioVideoService minioVideoService = org.mockito.Mockito.mock(MinioVideoService.class);
    private final OtaCdnService service = new OtaCdnService(minioVideoService, new ObjectMapper());

    private OtaRelease release(int grayPercent, String allowlistJson) {
        OtaRelease release = new OtaRelease();
        release.setGrayPercent(grayPercent);
        release.setDeviceAllowlist(allowlistJson);
        return release;
    }

    @Test
    void allowlistExcludesDevicesEvenWhenGray100() {
        OtaRelease release = release(100, "[\"CAB-001\",\"CAB-002\"]");
        assertTrue(service.isInGrayRollout("CAB-001", release));
        assertFalse(service.isInGrayRollout("CAB-003", release), "白名单外设备不应收到 gray=100 的发布");
    }

    @Test
    void blankAllowlistFallsBackToGrayPercent() {
        assertTrue(service.isInGrayRollout("CAB-001", release(100, null)));
        assertTrue(service.isInGrayRollout("CAB-001", release(100, "  ")));
        assertFalse(service.isInGrayRollout("CAB-001", release(0, null)));
    }

    @Test
    void malformedAllowlistFailsClosed() {
        // 白名单解析失败视为名单非空且无人匹配，绝不能回退为全量灰度
        assertFalse(service.isInGrayRollout("CAB-001", release(100, "not-json")));
    }

    @Test
    void grayPercentStillAppliesWithoutAllowlist() {
        OtaRelease release = release(100, null);
        assertTrue(service.isInGrayRollout("ANY-DEVICE", release));
    }
}
