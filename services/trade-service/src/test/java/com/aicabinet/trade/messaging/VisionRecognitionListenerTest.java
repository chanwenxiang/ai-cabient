package com.aicabinet.trade.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.common.enums.VisionIngestOutcome;
import com.aicabinet.trade.service.VisionResultIngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link VisionRecognitionListener} 钉住测试（审计 A2/P1-8 + 9.4-1）。
 *
 * <p>历史缺陷（修复前）：Kafka 面把 modelVersion 硬编 null（{@code blocksSilentSettle}
 * 精度闸被绕过）、零 deviceId 校验（能投消息即可为任意 RECOGNIZING 会话伪造识别结果
 * 静默扣款）。现监听器必须把 payload 转成与 HTTP /edge-results 相同的 DTO 走
 * {@code VisionResultIngestService.ingest}——两入口同一口径（铁律 51）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisionRecognitionListenerTest {

    private static final String VALID_PAYLOAD = """
            {
              "sessionId": "S-1",
              "deviceId": "500909625160",
              "taskId": "T-1",
              "items": [{"skuId": "SKU-A", "quantity": 2, "confidence": 0.9}],
              "overallConfidence": 0.88,
              "needReview": false,
              "modelVersion": "vision-v3.2.1",
              "detectedClasses": ["cola", "sprite"]
            }
            """;

    @Mock VisionResultIngestService ingestService;
    @Mock KafkaTemplate<String, String> kafkaTemplate;

    private VisionRecognitionListener listener;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        listener = new VisionRecognitionListener(ingestService, objectMapper, kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(ingestService.ingest(any()))
                .thenReturn(new VisionResultIngestResponseDto(
                        true, VisionIngestOutcome.PROCESSED, "ok", "S-1", "T-1", "COMPLETED"));
    }

    /** P1-8 核心钉住：modelVersion/detectedClasses 必须原样进入 DTO（精度闸不再被丢弃绕过）。 */
    @Test
    void onResult_parsesFullPayload_intoSharedIngestDto() throws Exception {
        listener.onResult(VALID_PAYLOAD);

        ArgumentCaptor<VisionRecognitionResultDto> captor =
                ArgumentCaptor.forClass(VisionRecognitionResultDto.class);
        verify(ingestService).ingest(captor.capture());
        VisionRecognitionResultDto dto = captor.getValue();
        assertEquals("S-1", dto.sessionId());
        assertEquals("500909625160", dto.deviceId(), "deviceId 必须随报文传入 ingest（9.4-1 会话绑定）");
        assertEquals("T-1", dto.taskId());
        assertEquals("vision-v3.2.1", dto.modelVersion(), "modelVersion 不得再被丢弃（P1-8）");
        assertEquals(List.of("cola", "sprite"), dto.detectedClasses());
        assertEquals(1, dto.items().size());
        assertEquals(2, dto.items().get(0).quantity());
        assertTrue(dto.items().get(0).skuId().equals("SKU-A"));
    }

    /** deviceId 不匹配等 ingest 拒绝 ⇒ 进 DLT（不静默放行，也不阻断 offset 提交）。 */
    @Test
    void onResult_ingestRejects_publishesDltWithOriginalPayload() {
        when(ingestService.ingest(any()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "device mismatch"));

        listener.onResult(VALID_PAYLOAD);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(KafkaTopics.VISION_RECOGNIZE_RESULT_DLT),
                payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains("S-1"), "DLT 信封保留 sessionId 便于人工回放");
    }

    /** TOO_EARLY/ALREADY_HANDLED 是正常业务结论：不进 DLT、不抛错（offset 正常提交）。 */
    @Test
    void onResult_notAdopted_noDltAndNoThrow() {
        when(ingestService.ingest(any()))
                .thenReturn(new VisionResultIngestResponseDto(
                        false, VisionIngestOutcome.TOO_EARLY, "尚未关门识别", "S-1", "T-1", "SHOPPING"));

        listener.onResult(VALID_PAYLOAD);

        verify(kafkaTemplate, never()).send(anyString(), anyString());
    }

    @Test
    void onResult_malformedJson_publishesDlt() {
        listener.onResult("not-json{");

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(KafkaTopics.VISION_RECOGNIZE_RESULT_DLT),
                anyString());
    }

    /** H40：DLT 也失败 ⇒ 重抛原异常阻止 offset 提交。 */
    @Test
    void onResult_dltPublishFails_rethrowsOriginal() {
        when(ingestService.ingest(any()))
                .thenThrow(new IllegalStateException("ingest exploded"));
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenAnswer(inv -> CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThrows(IllegalStateException.class, () -> listener.onResult(VALID_PAYLOAD));

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(KafkaTopics.VISION_RECOGNIZE_RESULT_DLT),
                anyString());
    }
}
