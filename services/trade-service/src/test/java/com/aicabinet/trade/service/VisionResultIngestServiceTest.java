package com.aicabinet.trade.service;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.common.enums.VisionIngestOutcome;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端侧识别结果直报入站。
 *
 * <p>两条最重要的判据：
 * <ol>
 *   <li>采纳路径必须与 Kafka 通道复用同一个结算方法（断言调用 {@code completeAsyncRecognition}）；
 *       非识别态一律<b>不得</b>再结算一次（断言 {@code never()}）—— 这是重复上报不重复扣款的护栏。</li>
 *   <li>{@code modelVersion} 留空必须被拒（400）—— 留空会绕过结算管线「非生产精度不得静默扣款」的判定。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisionResultIngestServiceTest {

    @Mock private ShoppingSessionMapper repository;
    @Mock private SessionService sessionService;
    @Mock private CabinetMetrics metrics;

    private VisionResultIngestService service;

    @BeforeEach
    void setUp() {
        service = new VisionResultIngestService(repository, sessionService, metrics);
    }

    // ---------- 采纳路径 ----------

    @Test
    void ingest_recognizing_reusesSharedSettlementAndReportsProcessed() {
        stubFindById("S-OK", SessionState.RECOGNIZING, SessionState.COMPLETED);

        VisionResultIngestResponseDto response = service.ingest(result("S-OK"));

        assertTrue(response.accepted());
        assertEquals(VisionIngestOutcome.PROCESSED, response.outcome());
        assertEquals("COMPLETED", response.sessionState());
        assertEquals("S-OK", response.sessionId());
        verify(sessionService, times(1)).completeAsyncRecognition(eq("S-OK"), any());
    }

    @Test
    void ingest_recognizingButDisputed_reportsProcessedWithDisputedState() {
        stubFindById("S-DSP", SessionState.RECOGNIZING, SessionState.DISPUTED);

        VisionResultIngestResponseDto response = service.ingest(result("S-DSP"));

        assertTrue(response.accepted());
        assertEquals(VisionIngestOutcome.PROCESSED, response.outcome());
        assertEquals("DISPUTED", response.sessionState());
    }

    @Test
    void ingest_mapsRequestIntoRecognitionResult() {
        stubFindById("S-MAP", SessionState.RECOGNIZING, SessionState.COMPLETED);

        service.ingest(new VisionRecognitionResultDto(
                "S-MAP", null, "trace-1",
                List.of(new VisionRecognitionResultDto.Item("SKU-A", 2, 0.93)),
                0.93, false, "QUECTEL-EDGE-1.2", List.of("cola"), "QUECTEL", Instant.now()));

        VisionServiceClient.RecognitionResult sent = capturedResult();
        assertEquals("T-S-MAP", sent.taskId(), "taskId 缺省时应与异步链路一致地补 T-<sessionId>");
        assertEquals(1, sent.items().size());
        assertEquals("SKU-A", sent.items().get(0).skuId());
        assertEquals(2, sent.items().get(0).quantity());
        assertEquals(0.93f, sent.items().get(0).confidence(), 0.001f);
        assertEquals("QUECTEL-EDGE-1.2", sent.modelVersion());
        assertEquals(List.of("cola"), sent.detectedClasses());
        assertFalse(sent.needReview());
    }

    @Test
    void ingest_explicitTaskId_isPreserved() {
        stubFindById("S-TASK", SessionState.RECOGNIZING, SessionState.COMPLETED);
        VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                "S-TASK", "T-EDGE-9", null, List.of(), 0.0, true,
                "QUECTEL-EDGE-1.2", null, "QUECTEL", null);

        service.ingest(request);

        assertEquals("T-EDGE-9", capturedResult().taskId());
    }

    @Test
    void ingest_nullItemsAndClasses_mapToEmptyCollections() {
        stubFindById("S-NULL", SessionState.RECOGNIZING, SessionState.DISPUTED);
        VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                "S-NULL", "T-1", null, null, 0.0, true, "QUECTEL-EDGE-1.2", null, null, null);

        service.ingest(request);

        VisionServiceClient.RecognitionResult sent = capturedResult();
        assertEquals(List.of(), sent.items());
        assertEquals(List.of(), sent.detectedClasses());
        assertTrue(sent.needReview(), "端侧自认需复核必须原样传给结算（不得被降级为自动扣款）");
    }

    // ---------- 幂等 / 非识别态 ----------

    @Test
    void ingest_completedSession_isNotSettledAgain() {
        ShoppingSession completed = session("S-DONE", SessionState.COMPLETED);
        when(repository.findById("S-DONE")).thenReturn(Optional.of(completed));

        VisionResultIngestResponseDto response = service.ingest(result("S-DONE"));

        assertFalse(response.accepted());
        assertEquals(VisionIngestOutcome.ALREADY_HANDLED, response.outcome());
        assertEquals("COMPLETED", response.sessionState());
        verify(sessionService, never()).completeAsyncRecognition(any(), any());
    }

    @Test
    void ingest_settlingAndDisputedAndFailed_areNotSettledAgain() {
        for (SessionState state : List.of(
                SessionState.SETTLING, SessionState.DISPUTED, SessionState.FAILED)) {
            String id = "S-" + state.name();
            when(repository.findById(id)).thenReturn(Optional.of(session(id, state)));

            VisionResultIngestResponseDto response = service.ingest(result(id));

            assertFalse(response.accepted());
            assertEquals(VisionIngestOutcome.ALREADY_HANDLED, response.outcome(), state.name());
        }
        verify(sessionService, never()).completeAsyncRecognition(any(), any());
    }

    @Test
    void ingest_shoppingAndWaitingUpload_areTooEarly() {
        for (SessionState state : List.of(
                SessionState.CREATED, SessionState.OPENING,
                SessionState.SHOPPING, SessionState.WAITING_UPLOAD)) {
            String id = "S-" + state.name();
            when(repository.findById(id)).thenReturn(Optional.of(session(id, state)));

            VisionResultIngestResponseDto response = service.ingest(result(id));

            assertFalse(response.accepted());
            assertEquals(VisionIngestOutcome.TOO_EARLY, response.outcome(), state.name());
        }
        verify(sessionService, never()).completeAsyncRecognition(any(), any());
    }

    @Test
    void ingest_cancelledSession_reportsCancelled() {
        when(repository.findById("S-CXL")).thenReturn(Optional.of(session("S-CXL", SessionState.CANCELLED)));

        VisionResultIngestResponseDto response = service.ingest(result("S-CXL"));

        assertFalse(response.accepted());
        assertEquals(VisionIngestOutcome.CANCELLED, response.outcome());
        verify(sessionService, never()).completeAsyncRecognition(any(), any());
    }

    @Test
    void ingest_secondDeliveryAfterProcessing_isIdempotent() {
        // 第一次：RECOGNIZING → 结算；第二次：已 COMPLETED → 拒绝重算
        stubFindById("S-IDEM", SessionState.RECOGNIZING, SessionState.COMPLETED);
        assertEquals(VisionIngestOutcome.PROCESSED, service.ingest(result("S-IDEM")).outcome());

        when(repository.findById("S-IDEM")).thenReturn(Optional.of(session("S-IDEM", SessionState.COMPLETED)));
        VisionResultIngestResponseDto second = service.ingest(result("S-IDEM"));

        assertEquals(VisionIngestOutcome.ALREADY_HANDLED, second.outcome());
        verify(sessionService, times(1)).completeAsyncRecognition(eq("S-IDEM"), any());
    }

    // ---------- 入口 fail-closed 校验 ----------

    @Test
    void ingest_blankModelVersion_rejected() {
        VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                "S-1", "T-1", null, List.of(), 0.9, false, "  ", List.of(), "QUECTEL", null);

        ResponseStatusException thrown =
                assertThrows(ResponseStatusException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("modelVersion"));
        verify(sessionService, never()).completeAsyncRecognition(any(), any());
    }

    @Test
    void ingest_nullModelVersion_rejected() {
        VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                "S-1", "T-1", null, List.of(), 0.9, false, null, List.of(), "QUECTEL", null);

        ResponseStatusException thrown =
                assertThrows(ResponseStatusException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(repository, never()).findById(any());
    }

    @Test
    void ingest_blankSessionId_rejected() {
        VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                " ", "T-1", null, List.of(), 0.9, false, "v1", List.of(), "QUECTEL", null);

        ResponseStatusException thrown =
                assertThrows(ResponseStatusException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        verify(repository, never()).findById(any());
    }

    @Test
    void ingest_blankSkuId_rejected() {
        VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                "S-1", "T-1", null, List.of(new VisionRecognitionResultDto.Item("  ", 1, 0.9)),
                0.9, false, "v1", List.of(), "QUECTEL", null);

        ResponseStatusException thrown =
                assertThrows(ResponseStatusException.class, () -> service.ingest(request));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("skuId"));
    }

    @Test
    void ingest_nonPositiveQuantity_rejected() {
        for (int quantity : new int[]{0, -3}) {
            VisionRecognitionResultDto request = new VisionRecognitionResultDto(
                    "S-1", "T-1", null,
                    List.of(new VisionRecognitionResultDto.Item("SKU-A", quantity, 0.9)),
                    0.9, false, "v1", List.of(), "QUECTEL", null);

            ResponseStatusException thrown =
                    assertThrows(ResponseStatusException.class, () -> service.ingest(request));

            assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
            assertTrue(thrown.getReason().contains("quantity"));
        }
    }

    @Test
    void ingest_unknownSession_returnsNotFound() {
        when(repository.findById("S-NONE")).thenReturn(Optional.empty());

        ResponseStatusException thrown =
                assertThrows(ResponseStatusException.class, () -> service.ingest(result("S-NONE")));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
        verify(sessionService, never()).completeAsyncRecognition(any(), any());
    }

    // ---------- 辅助 ----------

    private VisionServiceClient.RecognitionResult capturedResult() {
        ArgumentCaptor<VisionServiceClient.RecognitionResult> captor =
                ArgumentCaptor.forClass(VisionServiceClient.RecognitionResult.class);
        verify(sessionService).completeAsyncRecognition(any(), captor.capture());
        return captor.getValue();
    }

    /** 首次 findById 返回 before，之后返回 after（入站会读两次：采纳前状态、结算后终态）。 */
    private void stubFindById(String sessionId, SessionState before, SessionState after) {
        AtomicInteger calls = new AtomicInteger();
        Answer<Optional<ShoppingSession>> answer = invocation ->
                Optional.of(session(sessionId, calls.getAndIncrement() == 0 ? before : after));
        when(repository.findById(sessionId)).thenAnswer(answer);
    }

    private static VisionRecognitionResultDto result(String sessionId) {
        return new VisionRecognitionResultDto(
                sessionId, "T-1", "trace-1",
                List.of(new VisionRecognitionResultDto.Item("SKU-A", 1, 0.95)),
                0.95, false, "QUECTEL-EDGE-1.2", List.of("cola"), "QUECTEL", Instant.now());
    }

    private static ShoppingSession session(String id, SessionState state) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId(id);
        session.setUserId(7L);
        session.setDeviceId("CAB-001");
        session.setState(state);
        return session;
    }
}
