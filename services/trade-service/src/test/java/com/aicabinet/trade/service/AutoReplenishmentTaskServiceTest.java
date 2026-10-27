package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentRouteDto;
import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺货自动建补货任务（2026-10-27 用户拍板「柜子缺货了应该就在补货任务里」）：
 * 锁三件事——按阈值触发（建议量&gt;0 才建，suggestForDevice fillToPar=false 本身
 * 就是 low_threshold/minLevel/ROP 阈值口径）；同柜已有 PENDING/IN_PROGRESS 任务幂等跳过；
 * 总开关关闭时不扫不建。
 */
@ExtendWith(MockitoExtension.class)
class AutoReplenishmentTaskServiceTest {

    @Mock DeviceInfoMapper deviceRepository;
    @Mock ReplenishmentTaskMapper taskRepository;
    @Mock ReplenishmentService replenishmentService;
    @Mock SystemConfigService systemConfigService;
    @Mock ScheduledTaskService taskService;

    private AutoReplenishmentTaskService service;

    @BeforeEach
    void setUp() {
        service = new AutoReplenishmentTaskService(
                deviceRepository, taskRepository, replenishmentService,
                systemConfigService, taskService);
        lenient().when(systemConfigService.getBoolean(
                AutoReplenishmentTaskService.CONFIG_ENABLED, true)).thenReturn(true);
        lenient().when(taskService.tryBegin(
                eq(AutoReplenishmentTaskService.TASK_KEY), anyLong())).thenReturn(true);
    }

    private DeviceInfo device(String id) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        return d;
    }

    private ReplenishmentSuggestDto suggest(String skuId, int qty) {
        return new ReplenishmentSuggestDto("EG-1", skuId, 1, 4, 3, qty);
    }

    @Test
    void scan_thresholdGap_createsTask() {
        when(deviceRepository.selectList(any())).thenReturn(List.of(device("EG-1")));
        when(taskRepository.findByDeviceIdAndStatusIn("EG-1",
                List.of("PENDING", "IN_PROGRESS"))).thenReturn(List.of());
        when(replenishmentService.suggestForDevice("EG-1"))
                .thenReturn(List.of(suggest("100013", 2)));
        ReplenishmentRouteDto route = new ReplenishmentRouteDto(
                88L, "route", null, null, "", List.of(), null);
        when(replenishmentService.createTaskFromSuggestion(0L, "EG-1", null)).thenReturn(route);

        service.scanAndCreate();

        verify(replenishmentService).createTaskFromSuggestion(0L, "EG-1", null);
    }

    @Test
    void scan_noGap_doesNotCreate() {
        when(deviceRepository.selectList(any())).thenReturn(List.of(device("EG-2")));
        when(taskRepository.findByDeviceIdAndStatusIn("EG-2",
                List.of("PENDING", "IN_PROGRESS"))).thenReturn(List.of());
        when(replenishmentService.suggestForDevice("EG-2")).thenReturn(List.of());

        service.scanAndCreate();

        verify(replenishmentService, never()).createTaskFromSuggestion(
                anyLong(), anyString(), any());
    }

    @Test
    void scan_activeTaskExists_skipsDevice() {
        when(deviceRepository.selectList(any())).thenReturn(List.of(device("EG-3")));
        com.aicabinet.trade.domain.ReplenishmentTask active =
                new com.aicabinet.trade.domain.ReplenishmentTask();
        when(taskRepository.findByDeviceIdAndStatusIn("EG-3",
                List.of("PENDING", "IN_PROGRESS"))).thenReturn(List.of(active));

        service.scanAndCreate();

        verify(replenishmentService, never()).suggestForDevice(anyString());
        verify(replenishmentService, never()).createTaskFromSuggestion(
                anyLong(), anyString(), any());
    }

    @Test
    void scan_switchOff_doesNothing() {
        when(systemConfigService.getBoolean(AutoReplenishmentTaskService.CONFIG_ENABLED, true))
                .thenReturn(false);

        service.scanAndCreate();

        verify(taskService).finish(eq(AutoReplenishmentTaskService.TASK_KEY),
                eq("SUCCESS"), anyString(), anyLong());
        verify(deviceRepository, never()).selectList(any());
        verify(replenishmentService, never()).createTaskFromSuggestion(
                anyLong(), anyString(), any());
    }
}
