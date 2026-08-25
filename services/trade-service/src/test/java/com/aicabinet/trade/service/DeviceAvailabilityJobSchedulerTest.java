package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceAvailabilityJobSchedulerTest {

    @Test
    void fallbackRunsWhenTryBeginAllows() {
        DeviceStableOnlineAutoUnlockService autoUnlock = mock(DeviceStableOnlineAutoUnlockService.class);
        DeviceAvailabilityKpiService kpi = mock(DeviceAvailabilityKpiService.class);
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(anyString(), anyLong())).thenReturn(true);
        when(autoUnlock.autoUnlockStableOnlineDevices()).thenReturn(0);
        when(kpi.snapshotYesterday()).thenReturn(new DeviceAvailabilityKpiDto(
                LocalDate.parse("2026-08-18"), 3, 1, 0, 0, 0, null, null, null));
        DeviceAvailabilityJobScheduler scheduler = new DeviceAvailabilityJobScheduler(autoUnlock, kpi, tasks);

        scheduler.autoUnlockFallback();
        scheduler.kpiSnapshotFallback();

        verify(autoUnlock).autoUnlockStableOnlineDevices();
        verify(kpi).snapshotYesterday();
        verify(tasks).finish(eq("device-auto-unlock"), eq("SUCCESS"), eq("本次无自动解锁"), anyLong());
        verify(tasks).finish(eq("kpi-snapshot"), eq("SUCCESS"), contains("可用性快照"), anyLong());
    }

    @Test
    void fallbackSkipsWhenTryBeginDenies() {
        DeviceStableOnlineAutoUnlockService autoUnlock = mock(DeviceStableOnlineAutoUnlockService.class);
        DeviceAvailabilityKpiService kpi = mock(DeviceAvailabilityKpiService.class);
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(anyString(), anyLong())).thenReturn(false);
        DeviceAvailabilityJobScheduler scheduler = new DeviceAvailabilityJobScheduler(autoUnlock, kpi, tasks);

        scheduler.autoUnlockFallback();
        scheduler.kpiSnapshotFallback();

        verifyNoInteractions(autoUnlock, kpi);
    }
}
