package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SlaDailySnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlaMetricsConcurrencyTest {

    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private SlaDailySnapshotMapper snapshotRepository;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private DisputeTicketMapper disputeRepository;
    @Mock private DisputeSlaService disputeSlaService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;

    private SlaMetricsService service;

    @BeforeEach
    void setUp() {
        service = new SlaMetricsService(sessionRepository, deviceRepository, snapshotRepository,
                merchantScopeService, disputeRepository, disputeSlaService, distributedLockService, null, taskService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(taskService.tryBegin("sla-snapshot", 600L)).thenReturn(true);
        when(sessionRepository.countCreatedBetween(any(), any())).thenReturn(0L);
        when(sessionRepository.countCreatedBetweenAndStateIn(any(), any(), any())).thenReturn(0L);
        when(sessionRepository.avgDoorOpenMsBetween(any(), any())).thenReturn(0L);
        when(sessionRepository.p95DoorOpenMsBetween(any(), any())).thenReturn(0L);
        when(deviceRepository.count()).thenReturn(0L);
        when(deviceRepository.countByOnlineStatus("ONLINE")).thenReturn(0L);
    }

    @Test
    void snapshotDaily_whenLockBusy_skipsPersist() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(distributedLockService.tryLock(
                SlaMetricsService.slaSnapshotDailyLockKey(yesterday), 60L, 5L))
                .thenReturn(false);

        service.snapshotDaily();

        verify(snapshotRepository, never()).insert(any());
        verify(snapshotRepository, never()).updateById(any());
        verify(taskService).finish(eq("sla-snapshot"), eq("SUCCESS"), any(), anyLong());
    }
}
