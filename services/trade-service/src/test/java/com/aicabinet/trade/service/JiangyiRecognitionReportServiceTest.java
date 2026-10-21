package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiRecognitionReport;
import com.aicabinet.trade.mapper.JiangyiRecognitionReportMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 识别上报留档写入单测（CB-062）：append-only（只 insert）、
 * reportId 由序列生成（调用方带入的 id 必须被清空）、reportedAt 缺省补当前时间。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiRecognitionReportServiceTest {

    @Mock private JiangyiRecognitionReportMapper reportMapper;

    private JiangyiRecognitionReport report() {
        JiangyiRecognitionReport row = new JiangyiRecognitionReport();
        row.setDeviceId("D-1");
        row.setOrderNo("S-1");
        row.setFormsJson("[{\"classId\":7,\"quantity\":1,\"thirdPid\":1018,\"price\":350}]");
        row.setFormCount(1);
        row.setItemCount(1);
        row.setVendorAmountCents(350L);
        row.setOutcome(JiangyiRecognitionReport.OUTCOME_FORWARDED);
        return row;
    }

    @Test
    void archiveInsertsAndBackfillsReportedAt() {
        JiangyiRecognitionReport row = report();
        row.setReportId(999L); // 调用方不应带入 id（BIGSERIAL 由 DB 生成）
        Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);

        new JiangyiRecognitionReportService(reportMapper).archive(row);

        ArgumentCaptor<JiangyiRecognitionReport> captor =
                ArgumentCaptor.forClass(JiangyiRecognitionReport.class);
        verify(reportMapper, times(1)).insert(captor.capture());
        assertNull(captor.getValue().getReportId(), "自增主键不得由入参带入");
        assertNotNull(captor.getValue().getReportedAt());
        assertEquals(350L, captor.getValue().getVendorAmountCents());
        assertTrue(captor.getValue().getReportedAt().isAfter(before));
    }

    @Test
    void archiveKeepsCallerReportedAt() {
        JiangyiRecognitionReport row = report();
        Instant at = Instant.parse("2026-10-21T03:04:05Z");
        row.setReportedAt(at);

        new JiangyiRecognitionReportService(reportMapper).archive(row);

        ArgumentCaptor<JiangyiRecognitionReport> captor =
                ArgumentCaptor.forClass(JiangyiRecognitionReport.class);
        verify(reportMapper).insert(captor.capture());
        assertSame(at, captor.getValue().getReportedAt());
    }

    @Test
    void repeatedArrivalsAreSeparateInserts() {
        JiangyiRecognitionReportService service = new JiangyiRecognitionReportService(reportMapper);

        service.archive(report());
        service.archive(report());

        verify(reportMapper, times(2)).insert(any());
    }
}
