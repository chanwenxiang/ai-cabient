package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.RiskEvent;
import com.aicabinet.trade.mapper.RiskEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskAutoDispositionConcurrencyTest {

    @Mock private RiskEventMapper riskEventRepository;
    @Mock private SystemConfigService systemConfigService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;

    private RiskAutoDispositionService service;

    @BeforeEach
    void setUp() {
        service = new RiskAutoDispositionService(
                riskEventRepository, systemConfigService, distributedLockService, taskService);
    }

    @Test
    void autoClearInfo_whenLockBusy_skipsEvent() {
        when(systemConfigService.getInt("risk.auto_clear_info_hours", 72)).thenReturn(72);
        RiskEvent event = new RiskEvent();
        event.setEventId(11L);
        event.setSeverity("INFO");
        event.setDispositionStatus("OPEN");
        event.setCreatedAt(Instant.now().minusSeconds(3600 * 100));
        when(riskEventRepository.selectList(any())).thenReturn(List.of(event));
        when(distributedLockService.tryLock(
                RiskAutoDispositionService.riskEventLockKey(11L), 60L, 5L))
                .thenReturn(false);

        assertEquals(0, service.autoClearInfo());
        verify(riskEventRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void autoAckWarn_whenDispositionChanged_skipsUpdate() {
        when(systemConfigService.getInt("risk.auto_ack_warn_hours", 168)).thenReturn(168);
        RiskEvent event = new RiskEvent();
        event.setEventId(22L);
        event.setSeverity("WARN");
        event.setDispositionStatus("OPEN");
        event.setCreatedAt(Instant.now().minusSeconds(3600 * 200));
        when(riskEventRepository.selectList(any())).thenReturn(List.of(event));
        when(distributedLockService.tryLock(
                RiskAutoDispositionService.riskEventLockKey(22L), 60L, 5L))
                .thenReturn(true);
        RiskEvent alreadyHandled = new RiskEvent();
        alreadyHandled.setEventId(22L);
        alreadyHandled.setDispositionStatus("ACKED");
        when(riskEventRepository.findByIdForUpdate(22L)).thenReturn(Optional.of(alreadyHandled));

        assertEquals(0, service.autoAckWarn());
        verify(distributedLockService).unlock(RiskAutoDispositionService.riskEventLockKey(22L));
    }
}
