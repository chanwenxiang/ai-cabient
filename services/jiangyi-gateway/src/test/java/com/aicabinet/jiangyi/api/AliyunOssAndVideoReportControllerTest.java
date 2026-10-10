package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.oss.OssStsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * V16 §4.2.6 getTempUploadToken / §4.2.14 uploadVideoUrl 设备面端点行为。
 */
class AliyunOssAndVideoReportControllerTest {

    private static final String DEVICE_ID = "100000000001";

    private TradeInternalClient tradeInternalClient;
    private AliyunOssController ossController;
    private OrderVideoReportController videoController;
    private OssStsService ossStsService;

    @BeforeEach
    void setUp() {
        tradeInternalClient = Mockito.mock(TradeInternalClient.class);
        ossStsService = Mockito.mock(OssStsService.class);
        ossController = new AliyunOssController(ossStsService);
        videoController = new OrderVideoReportController(tradeInternalClient);
    }

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, DEVICE_ID);
        request.setAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER, "CQYB1003");
        return request;
    }

    // ---------- §4.2.6 ----------

    @Test
    void getTempUploadTokenReturnsDocumentAlignedEnvelope() {
        Mockito.when(ossStsService.issueUploadToken(DEVICE_ID)).thenReturn(new OssStsService.OssUploadToken(
                "STS.ak", "STS.sk", "STS.token", "2026-10-10T09:00:00Z",
                "https://oss-cn-shenzhen.aliyuncs.com", "ai-cabinet-by", "jiangyi-video/"));
        Map<String, Object> resp = ossController.getTempUploadToken(authedRequest());
        assertEquals(200, resp.get("status"));
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        // 文档表格 17 字段逐字对齐
        assertEquals("STS.ak", data.get("accessKeyId"));
        assertEquals("STS.sk", data.get("accessKeySecret"));
        assertEquals("STS.token", data.get("securityToken"));
        assertEquals("https://oss-cn-shenzhen.aliyuncs.com", data.get("endpoint"));
        assertEquals("ai-cabinet-by", data.get("bucketName"));
        assertEquals("jiangyi-video/", data.get("dirName"));
    }

    @Test
    void getTempUploadTokenFailsClosedWhenUnavailable() {
        // 未配置/签发失败 → 500 非 200（不给半可用凭证，设备侧重试）
        Mockito.when(ossStsService.issueUploadToken(DEVICE_ID)).thenReturn(null);
        Map<String, Object> resp = ossController.getTempUploadToken(authedRequest());
        assertEquals(500, resp.get("status"));
    }

    // ---------- §4.2.14 ----------

    @Test
    void uploadVideoUrlForwardsToTrade() {
        Map<String, Object> resp = videoController.uploadVideoUrl(authedRequest(),
                new OrderVideoReportController.UploadVideoUrlRequest(
                        "ORD123", 1, 2, List.of("http://oss/a.mp4", "http://oss/b.mp4")));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).orderVideoReport(DEVICE_ID, "ORD123", 1, 2,
                List.of("http://oss/a.mp4", "http://oss/b.mp4"));
    }

    @Test
    void uploadVideoUrlAllowsEmptyUrlList() {
        // 文档明示失败片 videoUrls 可为 []——照转落库留痕
        Map<String, Object> resp = videoController.uploadVideoUrl(authedRequest(),
                new OrderVideoReportController.UploadVideoUrlRequest("ORD123", 2, 2, List.of()));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).orderVideoReport(eq(DEVICE_ID), eq("ORD123"),
                eq(2), eq(2), anyList());
    }

    @Test
    void uploadVideoUrlRejectsMissingRequiredFields() {
        Map<String, Object> resp = videoController.uploadVideoUrl(authedRequest(),
                new OrderVideoReportController.UploadVideoUrlRequest(null, 1, 1, null));
        assertEquals(400, resp.get("status"));
        verify(tradeInternalClient, never()).orderVideoReport(
                anyString(), anyString(), anyInt(), anyInt(), anyList());
    }

    @Test
    void uploadVideoUrlTradeFailureStillReturns200() {
        // 转发失败不让设备侧看到 5xx（防重试风暴），日志暴露补偿——与 downloadModelNotify 同策略
        doThrow(new IllegalStateException("trade down")).when(tradeInternalClient)
                .orderVideoReport(anyString(), anyString(), anyInt(), anyInt(), anyList());
        Map<String, Object> resp = videoController.uploadVideoUrl(authedRequest(),
                new OrderVideoReportController.UploadVideoUrlRequest("ORD123", 1, 1, List.of("u")));
        assertEquals(200, resp.get("status"));
    }

    @Test
    void uploadVideoUrlTrimsOrderNo() {
        videoController.uploadVideoUrl(authedRequest(),
                new OrderVideoReportController.UploadVideoUrlRequest(" ORD123 ", 1, 1, null));
        verify(tradeInternalClient).orderVideoReport(eq(DEVICE_ID), eq("ORD123"),
                eq(1), eq(1), eq(List.of()));
    }

    @Test
    void nullBodyHandledGracefully() {
        Map<String, Object> resp = videoController.uploadVideoUrl(authedRequest(), null);
        assertEquals(400, resp.get("status"));
        assertTrue(resp.containsKey("msg"));
    }
}
