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
        Map<String, Object> resp = ossController.getTempUploadToken(authedRequest(), null);
        assertEquals(200, resp.get("status"));
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        // 文档表格 17 字段逐字对齐 + 云侧实有的 expiration（V15/V16 字段表均漏，log3 实证）
        assertEquals("STS.ak", data.get("accessKeyId"));
        assertEquals("STS.sk", data.get("accessKeySecret"));
        assertEquals("STS.token", data.get("securityToken"));
        assertEquals("2026-10-10T09:00:00Z", data.get("expiration"));
        assertEquals("https://oss-cn-shenzhen.aliyuncs.com", data.get("endpoint"));
        assertEquals("ai-cabinet-by", data.get("bucketName"));
        assertEquals("jiangyi-video/", data.get("dirName"));
    }

    @Test
    void getTempUploadTokenFailsClosedWhenUnavailable() {
        // 未配置/签发失败 → 500 非 200（不给半可用凭证，设备侧重试）
        Mockito.when(ossStsService.issueUploadToken(DEVICE_ID)).thenReturn(null);
        Map<String, Object> resp = ossController.getTempUploadToken(authedRequest(), null);
        assertEquals(500, resp.get("status"));
    }

    @Test
    void getTempTokenRealDevicePathMatchesUploadTokenBehavior() {
        // 真机实路径（log3 实证 tokenPath=/aliYunOss/getTempToken，body jiangyi-logs/machine）：
        // 两路径行为一致；body 只审计不采用（上传目标由返回凭证决定）
        Mockito.when(ossStsService.issueUploadToken(DEVICE_ID)).thenReturn(new OssStsService.OssUploadToken(
                "STS.ak", "STS.sk", "STS.token", "2026-10-10T09:00:00Z",
                "https://oss-cn-shenzhen.aliyuncs.com", "ai-cabinet-by", "jiangyi-video/"));
        Map<String, Object> resp = ossController.getTempToken(authedRequest(),
                Map.of("bucketName", "jiangyi-logs", "dirName", "machine"));
        assertEquals(200, resp.get("status"));
        Map<?, ?> data = (Map<?, ?>) resp.get("data");
        // 回的是我方桶凭证，不是设备请求里的将邑桶
        assertEquals("ai-cabinet-by", data.get("bucketName"));
        assertEquals("STS.token", data.get("securityToken"));
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

    // ---------- replenishmentHistoryVideo/create（G-18，2026-10-30 真机实路径）----------

    @Test
    void replenishmentHistoryVideoAcceptsRealDevicePayload() {
        // 2026-10-30 15:59 真机报文原样：单号键是 historyNo=补货会话 id（非 orderNo/transactionId），
        // videoUrls 为左右双摄数组——此前 404 丢 URL，兼容链必须覆盖到 historyNo
        Map<String, Object> body = Map.of(
                "historyNo", "1793346732967695429570",
                "serialNum", 1,
                "videoQuantity", 1,
                "videoUrls", List.of(
                        "https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/EG943322621391//1793347072473left_output.mp4",
                        "https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/EG943322621391//1793347072473right_output.mp4"));
        Map<String, Object> resp = videoController.replenishmentHistoryVideo(authedRequest(), body);
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).orderVideoReport(eq(DEVICE_ID), eq("1793346732967695429570"),
                eq(1), eq(1), anyList());
    }

    @Test
    void replenishmentHistoryVideoFallsBackToTransactionIdAndDefaults() {
        // setOrderNoVideo 同款报文习惯：无 orderNo 只有 transactionId；serial/videoQuantity 缺省回 1
        Map<String, Object> resp = videoController.replenishmentHistoryVideo(authedRequest(),
                Map.of("transactionId", " 1792657421176609136722 "));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).orderVideoReport(eq(DEVICE_ID), eq("1792657421176609136722"),
                eq(1), eq(1), eq(List.of()));
    }

    @Test
    void replenishmentHistoryVideoSplitsCommaSeparatedUrls() {
        Map<String, Object> resp = videoController.replenishmentHistoryVideo(authedRequest(),
                Map.of("orderNo", "ORD9", "videoUrls", "http://oss/l.mp4 , http://oss/r.mp4"));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).orderVideoReport(DEVICE_ID, "ORD9", 1, 1,
                List.of("http://oss/l.mp4", "http://oss/r.mp4"));
    }

    @Test
    void replenishmentHistoryVideoJunkIntegersFallBackToDefaults() {
        // 铁律固件不可信：serialNum="x" 不炸 500，回默认 1 照常落库
        Map<String, Object> resp = videoController.replenishmentHistoryVideo(authedRequest(),
                Map.of("orderNo", "ORD9", "serialNum", "x", "videoQuantity", "0"));
        assertEquals(200, resp.get("status"));
        verify(tradeInternalClient).orderVideoReport(DEVICE_ID, "ORD9", 1, 1, List.of());
    }

    @Test
    void replenishmentHistoryVideoMissingAllIdentifiersReturns400() {
        Map<String, Object> resp = videoController.replenishmentHistoryVideo(authedRequest(),
                Map.of("serialNum", 1, "videoUrls", List.of("http://oss/a.mp4")));
        assertEquals(400, resp.get("status"));
        verify(tradeInternalClient, never()).orderVideoReport(
                anyString(), anyString(), anyInt(), anyInt(), anyList());
    }

    @Test
    void replenishmentHistoryVideoTradeFailureStillReturns200() {
        doThrow(new IllegalStateException("trade down")).when(tradeInternalClient)
                .orderVideoReport(anyString(), anyString(), anyInt(), anyInt(), anyList());
        Map<String, Object> resp = videoController.replenishmentHistoryVideo(authedRequest(),
                Map.of("orderNo", "ORD9"));
        assertEquals(200, resp.get("status"));
    }
}
