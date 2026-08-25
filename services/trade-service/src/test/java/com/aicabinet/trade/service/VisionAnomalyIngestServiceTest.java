package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.VisionAnomalyEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisionAnomalyIngestServiceTest {

    @Mock private OpsExceptionService opsExceptionService;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private DistributedLockService distributedLockService;

    private VisionAnomalyIngestService service;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(60L), org.mockito.ArgumentMatchers.eq(5L)))
                .thenReturn(true);
        service = new VisionAnomalyIngestService(opsExceptionService, opsAlertDispatcher, distributedLockService);
    }

    @Test
    void ingest_shouldReportHighSeverityAndPushAlert() {
        OpsExceptionDto created = mock(OpsExceptionDto.class);
        when(opsExceptionService.report(
                eq("VISION_ANOMALY"), eq("HIGH"), eq("CAB-001"), eq("S1"),
                isNull(), isNull(), eq("防撬告警"), contains("识别来源：QUECTEL")))
                .thenReturn(created);

        List<OpsExceptionDto> out = service.ingest(List.of(
                new VisionAnomalyEventDto("CAB-001", "S1", "TAMPER", 0.97,
                        "柜门被撬", "QUECTEL", Instant.now())));

        assertEquals(1, out.size());
        verify(opsAlertDispatcher).send(
                eq("VISION_ANOMALY"),
                contains("防撬告警"),
                contains("识别来源：QUECTEL"),
                anyMap());
    }

    @Test
    void ingest_shouldNotPushMediumSeverity() {
        when(opsExceptionService.report(
                eq("VISION_ANOMALY"), eq("MEDIUM"), eq("CAB-002"), isNull(),
                isNull(), isNull(), eq("遮挡识别"), contains("识别来源：QUECTEL")))
                .thenReturn(mock(OpsExceptionDto.class));

        service.ingest(List.of(
                new VisionAnomalyEventDto("CAB-002", null, "OCCLUSION", 0.81,
                        null, "QUECTEL", Instant.now())));

        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void ingest_shouldSkipBlankDevice() {
        List<OpsExceptionDto> out = service.ingest(List.of(
                new VisionAnomalyEventDto(" ", "S1", "TAMPER", 0.9, null, "QUECTEL", Instant.now())));

        assertEquals(0, out.size());
        verify(opsExceptionService, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void ingest_shouldIgnoreNullAndEmptyList() {
        assertEquals(0, service.ingest(null).size());
        assertEquals(0, service.ingest(List.of()).size());
    }
}
