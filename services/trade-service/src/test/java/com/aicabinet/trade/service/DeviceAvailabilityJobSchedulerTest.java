package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceAvailabilityJobSchedulerTest {

    @Test
    void fallbackRunsWhenXxlJobDisabled() {
        DeviceStableOnlineAutoUnlockService autoUnlock = mock(DeviceStableOnlineAutoUnlockService.class);
        DeviceAvailabilityKpiService kpi = mock(DeviceAvailabilityKpiService.class);
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(anyString(), anyLong())).thenReturn(true);
        DeviceAvailabilityJobScheduler scheduler = new DeviceAvailabilityJobScheduler(autoUnlock, kpi, tasks, false);

        scheduler.autoUnlockFallback();
        scheduler.kpiSnapshotFallback();

        verify(autoUnlock).autoUnlockStableOnlineDevices();
        verify(kpi).snapshotYesterday();
    }

    @Test
    void fallbackYieldsWhenXxlJobEnabled() {
        DeviceStableOnlineAutoUnlockService autoUnlock = mock(DeviceStableOnlineAutoUnlockService.class);
        DeviceAvailabilityKpiService kpi = mock(DeviceAvailabilityKpiService.class);
        DeviceAvailabilityJobScheduler scheduler = new DeviceAvailabilityJobScheduler(autoUnlock, kpi, mock(ScheduledTaskService.class), true);

        scheduler.autoUnlockFallback();
        scheduler.kpiSnapshotFallback();

        verifyNoInteractions(autoUnlock, kpi);
    }
}
