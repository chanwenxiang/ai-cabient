package com.aicabinet.jiangyi.api;

import com.aicabinet.common.enums.VisionIngestOutcome;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.normalize.RecognitionNormalizer;
import com.aicabinet.jiangyi.tracker.CommandTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 识别上报端点单测（CB-062 留档 + 既有转发/fail-closed 语义）。
 *
 * <p>核心契约：<b>每个到达分支都留档一行</b>（含原文 forms 与处置码），而留档失败
 * 只记 warn、绝不影响结算主链路；同一单重复上报每次都产生一行（append-only，无 UPDATE）。</p>
 */
class DeviceReportControllerTest {

    private static final String DEVICE_ID = "100000000001";
    private static final String SESSION_ID = "S-2026-0001";

    private TradeInternalClient trade;
    private CommandTracker commandTracker;
    private DeviceReportController controller;

    @BeforeEach
    void setUp() {
        trade = Mockito.mock(TradeInternalClient.class);
        commandTracker = Mockito.mock(CommandTracker.class);
        controller = new DeviceReportController(trade, new RecognitionNormalizer(trade),
                commandTracker, new ObjectMapper());
        when(trade.device(DEVICE_ID)).thenReturn(Optional.empty());
        // 默认按「已复核」跑既有用例；未复核闸口单列用例（V400）
        when(trade.mappingStatus(DEVICE_ID)).thenReturn(Optional.of(
                new TradeInternalClient.MappingStatusView(true, "ver-1", "ver-1", null)));
    }

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, DEVICE_ID);
        return request;
    }

    private static RecognitionNormalizer.Form form(Integer classId, Integer quantity,
                                                   Integer thirdPid, Integer price) {
        return new RecognitionNormalizer.Form(classId, quantity, thirdPid, price,
                "可乐 330ml", null, "6901234567890", "cola", null, "1");
    }

    private TradeInternalClient.RecognitionReportRequest captureArchive() {
        ArgumentCaptor<TradeInternalClient.RecognitionReportRequest> captor =
                ArgumentCaptor.forClass(TradeInternalClient.RecognitionReportRequest.class);
        verify(trade).postRecognitionReport(captor.capture());
        return captor.getValue();
    }

    @Test
    void forwardedReportArchivesRawFormsWithVendorAmount() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.of("SKU-1018"));
        when(trade.resolveSkuByProduct("1019")).thenReturn(Optional.of("SKU-1019"));
        when(trade.edgeResults(any())).thenReturn(new VisionResultIngestResponseDto(
                true, VisionIngestOutcome.PROCESSED, "ok", SESSION_ID, null, "COMPLETED"));

        Map<String, Object> resp = controller.addRecognitionGoods(authedRequest(),
                new DeviceReportController.RecognitionRequest(SESSION_ID, "TX-9", "WECHAT_SCORE",
                        List.of(form(7, 2, 1018, 350), form(9, 1, 1019, 500))));

        assertEquals(200, resp.get("status"));
        TradeInternalClient.RecognitionReportRequest archived = captureArchive();
        assertEquals(DEVICE_ID, archived.deviceId());
        assertEquals(SESSION_ID, archived.orderNo());
        assertEquals("TX-9", archived.transactionId());
        assertEquals("WECHAT_SCORE", archived.payType());
        assertEquals("FORWARDED", archived.outcome());
        assertEquals(2, archived.formCount());
        assertEquals(2, archived.itemCount());
        assertEquals(1200L, archived.vendorAmountCents(), "Σ(350×2 + 500×1) 只留档");
        assertTrue(archived.formsJson().contains("\"thirdPid\":1018"), "原文必须含 thirdPid");
        assertTrue(archived.formsJson().contains("\"price\":350"), "原文必须含厂商 price");
        assertTrue(archived.itemsJson().contains("SKU-1018"), "我方归一化结果并排留档");
        verify(commandTracker).complete(eq(CommandTracker.Kind.CLOSE), eq(SESSION_ID), eq(DEVICE_ID));
    }

    @Test
    void mappingMissStillArchivesWholeRawReport() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.empty());
        when(trade.resolveMapping(DEVICE_ID, 7)).thenReturn(Optional.empty());

        controller.addRecognitionGoods(authedRequest(), new DeviceReportController.RecognitionRequest(
                SESSION_ID, null, null, List.of(form(7, 1, 1018, 350))));

        TradeInternalClient.RecognitionReportRequest archived = captureArchive();
        assertEquals("MAPPING_MISS", archived.outcome());
        assertNull(archived.itemsJson(), "未转发结算 → 无归一化结果");
        assertTrue(archived.formsJson().contains("\"barCode\":\"6901234567890\""));
        verify(trade, never()).edgeResults(any());
        // 成因必须透传到 trade：旧实现不带 cause，映射未命中与「设备压根没回」在争议单里同一个文案
        verify(trade).recognizeTimeout(eq(SESSION_ID), eq(DEVICE_ID),
                eq(TradeInternalClient.CAUSE_CLASS_MAPPING_MISS),
                argThat(d -> d.contains("classId=7") && d.contains("thirdPid=1018")
                        && d.contains("qty=1")));
    }

    /** V400：柜机 classes 版本未人工复核 ⇒ 整单 fail-closed；thirdPid 通路也不得绕过（H-27）。 */
    @Test
    void unverifiedMappingFailsClosedBeforeNormalization() {
        when(trade.mappingStatus(DEVICE_ID)).thenReturn(Optional.of(
                new TradeInternalClient.MappingStatusView(false, "classes-new", "classes-old", null)));

        controller.addRecognitionGoods(authedRequest(), new DeviceReportController.RecognitionRequest(
                SESSION_ID, null, null, List.of(form(7, 1, 1018, 350))));

        TradeInternalClient.RecognitionReportRequest archived = captureArchive();
        assertEquals("MAPPING_UNVERIFIED", archived.outcome());
        assertNull(archived.itemsJson(), "未复核不产生归一化结果");
        verify(trade, never()).resolveSkuByProduct(anyString());
        verify(trade, never()).edgeResults(any());
        verify(trade).recognizeTimeout(eq(SESSION_ID), eq(DEVICE_ID),
                eq(TradeInternalClient.CAUSE_MAPPING_UNVERIFIED),
                argThat(d -> d.contains("classes-new") && d.contains("classes-old")));
    }

    @Test
    void emptyFormsArchivesAsEmpty() {
        controller.addRecognitionGoods(authedRequest(), new DeviceReportController.RecognitionRequest(
                SESSION_ID, null, null, List.of()));

        TradeInternalClient.RecognitionReportRequest archived = captureArchive();
        assertEquals("EMPTY_FORMS", archived.outcome());
        assertEquals(0, archived.formCount());
        assertNull(archived.vendorAmountCents(), "空报文不带价 → 与 0 元单区分");
        verify(trade).recognizeTimeout(eq(SESSION_ID), eq(DEVICE_ID),
                eq(TradeInternalClient.CAUSE_EMPTY_FORMS), isNull());
    }

    /** 真机 18:11 形态：设备直接回异常单（errorMsg=人工复核），识别上报从未到达——原文必须透传给 trade。 */
    @Test
    void deviceErrorOrderPassesVendorErrorMsg() {
        controller.uploadOrderError(authedRequest(),
                new DeviceReportController.OrderErrorRequest(SESSION_ID, "人工复核", null, null, null));

        verify(trade).recognizeTimeout(SESSION_ID, DEVICE_ID,
                TradeInternalClient.CAUSE_DEVICE_ERROR_ORDER, "人工复核");
    }

    /** errorMsg 只有四个字，成因在 errorDetailMessage（固件 ErrorOrderEntity 字段）——不采就无从判因。 */
    @Test
    void deviceErrorOrderAppendsErrorDetailMessage() {
        controller.uploadOrderError(authedRequest(), new DeviceReportController.OrderErrorRequest(
                SESSION_ID, "人工复核", " 轨迹甄别置位 ", null, null));

        verify(trade).recognizeTimeout(SESSION_ID, DEVICE_ID,
                TradeInternalClient.CAUSE_DEVICE_ERROR_ORDER, "人工复核｜轨迹甄别置位");
    }

    /** 只带 detail 不带 errorMsg（§4.2.11 errorMsg 为可选 0）时不得拼出前导分隔符。 */
    @Test
    void deviceErrorOrderWithoutMsgPassesDetailAlone() {
        controller.uploadOrderError(authedRequest(), new DeviceReportController.OrderErrorRequest(
                SESSION_ID, null, " 仅详情 ", null, null));

        verify(trade).recognizeTimeout(SESSION_ID, DEVICE_ID,
                TradeInternalClient.CAUSE_DEVICE_ERROR_ORDER, "仅详情");
    }

    @Test
    void blankOrderNoArchivesWithNullOrderNoAndNoDoorEvent() {
        Map<String, Object> resp = controller.addRecognitionGoods(authedRequest(),
                new DeviceReportController.RecognitionRequest("  ", null, null,
                        List.of(form(7, 1, 1018, 350))));

        assertEquals(200, resp.get("status"));
        TradeInternalClient.RecognitionReportRequest archived = captureArchive();
        assertEquals("BLANK_ORDERNO", archived.outcome());
        assertNull(archived.orderNo());
        assertNull(archived.itemsJson());
        verify(trade, never()).doorEvent(any());
    }

    @Test
    void forwardFailureArchivesWithoutCompletingWatchdog() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.of("SKU-1018"));
        when(trade.edgeResults(any())).thenReturn(null);

        controller.addRecognitionGoods(authedRequest(), new DeviceReportController.RecognitionRequest(
                SESSION_ID, null, null, List.of(form(7, 1, 1018, 350))));

        assertEquals("FORWARD_FAILED", captureArchive().outcome());
        verify(commandTracker, never()).complete(any(), anyString(), anyString());
    }

    @Test
    void archiveFailureNeverBreaksSettlement() {
        when(trade.resolveSkuByProduct("1018")).thenReturn(Optional.of("SKU-1018"));
        when(trade.edgeResults(any())).thenReturn(new VisionResultIngestResponseDto(
                true, VisionIngestOutcome.PROCESSED, "ok", SESSION_ID, null, "COMPLETED"));
        Mockito.doThrow(new IllegalStateException("trade archive endpoint down"))
                .when(trade).postRecognitionReport(any());

        Map<String, Object> resp = controller.addRecognitionGoods(authedRequest(),
                new DeviceReportController.RecognitionRequest(SESSION_ID, null, null,
                        List.of(form(7, 1, 1018, 350))));

        assertEquals(200, resp.get("status"), "存档失败不得让设备侧看到非 200 而重报");
        verify(trade).edgeResults(any());
    }

    @Test
    void repeatedReportsEachArchiveTheirOwnRow() {
        // 二次甄别复用同一端点：幂等作用于结算（trade 侧），留档必须一到达一行
        when(trade.resolveSkuByProduct(anyString())).thenReturn(Optional.of("SKU-1018"));
        when(trade.edgeResults(any())).thenReturn(new VisionResultIngestResponseDto(
                true, VisionIngestOutcome.PROCESSED, "ok", SESSION_ID, null, "COMPLETED"));
        DeviceReportController.RecognitionRequest body =
                new DeviceReportController.RecognitionRequest(SESSION_ID, null, null,
                        List.of(form(7, 1, 1018, 350)));

        controller.addRecognitionGoods(authedRequest(), body);
        controller.addRecognitionGoods(authedRequest(), body);

        verify(trade, Mockito.times(2)).postRecognitionReport(any());
    }

    @Test
    void legacySectionFourShapeWithoutPriceArchivesNullAmount() {
        // §4.2.10 普通对接形态：报文只有 classId/quantity，不带 thirdPid/price
        when(trade.resolveMapping(DEVICE_ID, 7)).thenReturn(Optional.of(
                new TradeInternalClient.MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL")));
        when(trade.edgeResults(any())).thenReturn(new VisionResultIngestResponseDto(
                true, VisionIngestOutcome.PROCESSED, "ok", SESSION_ID, null, "COMPLETED"));

        controller.addRecognitionGoods(authedRequest(), new DeviceReportController.RecognitionRequest(
                SESSION_ID, null, null,
                List.of(new RecognitionNormalizer.Form(7, 3, null, null, null, null, null, null, null, null))));

        TradeInternalClient.RecognitionReportRequest archived = captureArchive();
        assertEquals("FORWARDED", archived.outcome());
        assertEquals(1, archived.formCount());
        assertEquals(1, archived.itemCount());
        assertNull(archived.vendorAmountCents(), "报文不带价 ≠ 0 元单");
        assertTrue(archived.formsJson().contains("\"classId\":7"));
        assertTrue(archived.itemsJson().contains("SKU-7"));
    }
}
