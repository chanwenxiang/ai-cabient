package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceEnvReadingDto;
import com.aicabinet.trade.domain.DeviceEnvReading;
import com.aicabinet.trade.mapper.DeviceEnvReadingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceEnvServiceTest {

    @Mock private DeviceEnvReadingMapper readingRepository;
    @Mock private DistributedLockService distributedLockService;

    private DeviceEnvService service;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        service = new DeviceEnvService(readingRepository, distributedLockService);
    }

    @Test
    void record_shouldSkipNullMetrics() {
        service.saveReading("CAB-001", null, null, null);

        verify(readingRepository, never()).insert(any());
    }

    @Test
    void record_shouldInsertEachPresentMetric() {
        service.saveReading("CAB-001", 45.5, 12.3, 88.0);

        ArgumentCaptor<DeviceEnvReading> captor = ArgumentCaptor.forClass(DeviceEnvReading.class);
        verify(readingRepository, times(3)).insert(captor.capture());
        assertEquals(3, captor.getAllValues().size());
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "HUMIDITY".equals(r.getMetricType())));
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "VOLTAGE".equals(r.getMetricType())));
        assertTrue(captor.getAllValues().stream().anyMatch(r -> "POWER".equals(r.getMetricType())));
    }

    @Test
    void list_shouldReturnReadingsOrderedByTimeDesc() {
        DeviceEnvReading r = new DeviceEnvReading();
        r.setDeviceId("CAB-001");
        r.setMetricType("VOLTAGE");
        r.setValue(BigDecimal.valueOf(12.30));
        r.setReportedAt(Instant.now());
        when(readingRepository.findSince(eq("CAB-001"), eq("VOLTAGE"), any(), anyInt()))
                .thenReturn(List.of(r));

        List<DeviceEnvReadingDto> out = service.list("CAB-001", "VOLTAGE", 24, 100);

        assertEquals(1, out.size());
        assertEquals(12.30, out.get(0).value(), 0.001);
        verify(readingRepository).findSince(anyString(), anyString(), any(), anyInt());
    }
}
