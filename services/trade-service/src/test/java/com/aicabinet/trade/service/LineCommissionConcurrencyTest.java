package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineCommissionConcurrencyTest {

    @Mock private LineManagerMapper managerMapper;
    @Mock private LineDeviceMapper deviceMapper;
    @Mock private CabinetOrderMapper orderMapper;
    @Mock private LineCommissionDailyMapper commissionDailyMapper;
    @Mock private LineWalletService lineWalletService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;

    private LineCommissionJob job;

    @BeforeEach
    void setUp() {
        job = new LineCommissionJob(managerMapper, deviceMapper, orderMapper,
                commissionDailyMapper, lineWalletService, distributedLockService);
        ReflectionTestUtils.setField(job, "taskService", taskService);
        when(taskService.tryBegin(eq("line-commission"), eq(1800L))).thenReturn(true);
    }

    @Test
    void postDailyCommission_whenRowLockBusy_skipsCredit() {
        LineDevice binding = new LineDevice();
        binding.setManagerId(7L);
        binding.setDeviceId("CAB-LC");
        LineManager manager = new LineManager();
        manager.setManagerId(7L);
        manager.setStatus(LineManagerService.STATUS_ACTIVE);
        manager.setCommissionRateBps(100);
        manager.setCommissionFixedCents(0);
        when(deviceMapper.findByStatus(LineManagerService.STATUS_ACTIVE)).thenReturn(List.of(binding));
        when(managerMapper.findById(7L)).thenReturn(Optional.of(manager));
        LocalDate bizDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        when(distributedLockService.tryLock(
                eq(LineCommissionJob.lineCommissionDailyLockKey(7L, "CAB-LC", bizDate)), eq(60L), eq(5L)))
                .thenReturn(false);

        job.postDailyCommission();

        verify(lineWalletService, never()).credit(any(Long.class), any(Long.class), any(), any(), any(), any());
    }
}
