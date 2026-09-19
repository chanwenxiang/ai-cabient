package com.aicabinet.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.RecognitionResult;
import com.aicabinet.trade.mapper.RecognitionResultMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RecognitionResultWriter} 单测。
 *
 * <p>只覆盖写入器自身的判据：列约束前置校验、两把幂等闸（task_id / 会话）、items JSON 形状、
 * 以及 fail-hard 语义（不吞异常）。钩子是否真的被结算路径调用由 e2e 取证；同事务语义与外键锁的
 * 兼容性只有真实栈能验（见写入器类注释里的对照实验）。
 *
 * <p>默认桩用 {@code lenient}：这样 A/B 漂移（摘掉某道闸）只会产生<b>断言级红</b>，
 * 而不是「桩没用上」或 NPE 那种与判据无关的杂音。
 */
@ExtendWith(MockitoExtension.class)
class RecognitionResultWriterTest {

    @Mock RecognitionResultMapper mapper;
    @Mock CabinetMetrics metrics;

    private RecognitionResultWriter writer;

    @BeforeEach
    void setUp() {
        writer = new RecognitionResultWriter(mapper, new ObjectMapper(), metrics);
        // 默认：库里没有同一 task、会话也没有记录、写入成功（1 行）。各用例按需覆写。
        lenient().when(mapper.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(mapper.findBySessionId(anyString())).thenReturn(Optional.empty());
        lenient().when(mapper.insertIgnoreConflict(any())).thenReturn(1);
    }

    private static VisionServiceClient.RecognitionResult recognized(String taskId, String modelVersion) {
        return new VisionServiceClient.RecognitionResult(
                taskId,
                List.of(new VisionServiceClient.RecognizedItem("SKU-1", 2, 0.93f)),
                0.93f,
                false,
                modelVersion,
                List.of("cola"));
    }

    @Test
    void persist_writesRowWithAllColumnsMapped() {
        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("T-1", "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.WRITTEN, outcome);
        ArgumentCaptor<RecognitionResult> captor = ArgumentCaptor.forClass(RecognitionResult.class);
        verify(mapper).insertIgnoreConflict(captor.capture());
        RecognitionResult row = captor.getValue();
        assertEquals("T-1", row.getTaskId());
        assertEquals("S-1", row.getSessionId());
        assertEquals("[{\"skuId\":\"SKU-1\",\"quantity\":2,\"confidence\":0.93}]", row.getItems());
        assertEquals(0.93f, row.getOverallConfidence());
        assertEquals("VISION", row.getFusionMode());
        assertEquals("edge-v1", row.getModelVersion());
        assertEquals(Boolean.FALSE, row.getNeedReview());
        assertNull(row.getCreatedAt(), "created_at 必须留空，交由列默认 NOW() 兜底");
    }

    /**
     * items 的 JSON 键必须是 {@code quantity}——与 Kafka 报文解析（{@code VisionRecognitionListener}）
     * 和端侧直报 DTO 一致。V222 demo seed 用的是 {@code qty}+{@code skuName}，若跟着它写，
     * 读这份数据的消费者会取不到数量。此断言即为防这种漂移。
     */
    @Test
    void persist_itemsJsonUsesQuantityKeyAndNotQty() {
        writer.persist("S-1", recognized("T-1", "edge-v1"));

        ArgumentCaptor<RecognitionResult> captor = ArgumentCaptor.forClass(RecognitionResult.class);
        verify(mapper).insertIgnoreConflict(captor.capture());
        String json = captor.getValue().getItems();
        assertTrue(json.contains("\"quantity\""), "items JSON 必须用 quantity 键，实际=" + json);
        assertFalse(json.contains("\"qty\""), "items JSON 不得用 qty 键，实际=" + json);
    }

    @Test
    void persist_sameTaskIdTwice_skipsSecondWrite() {
        when(mapper.findById("T-1")).thenReturn(Optional.of(new RecognitionResult()));

        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("T-1", "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.DUPLICATE_TASK, outcome);
        verify(mapper, never()).insertIgnoreConflict(any());
    }

    @Test
    void persist_sessionAlreadyHasResult_skips() {
        when(mapper.findBySessionId("S-1")).thenReturn(Optional.of(new RecognitionResult()));

        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("T-1", "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.SESSION_ALREADY_RECORDED, outcome);
        verify(mapper, never()).insertIgnoreConflict(any());
    }

    /** 跨会话复用同一 task_id：ON CONFLICT DO NOTHING 生效（影响 0 行），不得抛错。 */
    @Test
    void persist_taskIdConflictAtInsert_returnsDuplicateWithoutThrowing() {
        when(mapper.insertIgnoreConflict(any())).thenReturn(0);

        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("T-1", "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.DUPLICATE_TASK, outcome);
    }

    /** Kafka 通道的 modelVersion 永远为 null（见 {@code VisionRecognitionListener}），必须允许落库。 */
    @Test
    void persist_nullModelVersion_stillWrites() {
        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("T-1", null));

        assertEquals(RecognitionResultWriter.Outcome.WRITTEN, outcome);
        ArgumentCaptor<RecognitionResult> captor = ArgumentCaptor.forClass(RecognitionResult.class);
        verify(mapper).insertIgnoreConflict(captor.capture());
        assertNull(captor.getValue().getModelVersion());
    }

    @Test
    void persist_blankTaskId_invalidAndNeverTouchesDb() {
        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("  ", "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.INVALID, outcome);
        verify(mapper, never()).insertIgnoreConflict(any());
        verify(mapper, never()).findById(anyString());
    }

    @Test
    void persist_taskIdOverColumnWidth_invalidAndNeverTouchesDb() {
        String tooLong = "T".repeat(RecognitionResultWriter.TASK_ID_MAX_LENGTH + 1);

        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized(tooLong, "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.INVALID, outcome);
        verify(mapper, never()).insertIgnoreConflict(any());
    }

    @Test
    void persist_modelVersionOverColumnWidth_invalidAndNeverTouchesDb() {
        String tooLong = "v".repeat(VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH + 1);

        RecognitionResultWriter.Outcome outcome = writer.persist("S-1", recognized("T-1", tooLong));

        assertEquals(RecognitionResultWriter.Outcome.INVALID, outcome);
        verify(mapper, never()).insertIgnoreConflict(any());
    }

    @Test
    void persist_emptyItems_writesEmptyJsonArray() {
        VisionServiceClient.RecognitionResult empty = new VisionServiceClient.RecognitionResult(
                "T-1", null, 0f, true, "edge-v1", List.of());

        assertEquals(RecognitionResultWriter.Outcome.WRITTEN, writer.persist("S-1", empty));

        ArgumentCaptor<RecognitionResult> captor = ArgumentCaptor.forClass(RecognitionResult.class);
        verify(mapper).insertIgnoreConflict(captor.capture());
        assertEquals("[]", captor.getValue().getItems());
    }

    /**
     * fail-hard：真实库错误必须外溢（让结算一起回滚），不得吞掉。若这里改成「吞掉并返回失败」，
     * 就会出现「已扣款却没有识别记录」的对账黑洞——正是本批要消灭的形态。
     */
    @Test
    void persist_insertFails_propagatesInsteadOfSwallowing() {
        doThrow(new IllegalStateException("db down")).when(mapper).insertIgnoreConflict(any());

        assertThrows(IllegalStateException.class, () -> writer.persist("S-1", recognized("T-1", "edge-v1")));
    }

    @Test
    void persist_nullRecognition_invalid() {
        assertEquals(RecognitionResultWriter.Outcome.INVALID, writer.persist("S-1", null));
        verify(mapper, never()).insertIgnoreConflict(any());
    }

    /**
     * 质量指标（P0-1 阶段 B）只在**真正写入**那一支记一次。
     *
     * <p>为什么这条重要：{@code need_review} 率与置信度分布都拿这个计数当分母，而端侧重投/同会话
     * 重复上报是常态。若在入口就记，每次重投都会把分母抬高，把「需复核率」算低 ⇒ 监控指标系统性偏乐观。
     */
    @Test
    void persist_recordsQualityMetricsOnlyWhenActuallyWritten() {
        writer.persist("S-1", recognized("T-1", "edge-v1"));
        verify(metrics).recordRecognition(0.93f, false);

        reset(metrics);
        when(mapper.findBySessionId("S-2")).thenReturn(Optional.of(new RecognitionResult()));
        RecognitionResultWriter.Outcome outcome = writer.persist("S-2", recognized("T-2", "edge-v1"));

        assertEquals(RecognitionResultWriter.Outcome.SESSION_ALREADY_RECORDED, outcome);
        verify(metrics, never()).recordRecognition(any(), anyBoolean());
    }
}
