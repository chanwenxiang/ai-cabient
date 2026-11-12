package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时清扫（R20 P2-2）：条件更新取代扫描快照直写。
 *
 * <p>旧实现拿扫描到的 IN_PROGRESS 快照无条件 {@code setStatus(CANCELLED)} + save：
 * 扫描到落库之间的几秒里补货员完成任务（COMPLETED），状态会被改回 CANCELLED，
 * 连带关掉他的会话、把路线按取消收口，还会发一条「存在已上架明细」的告警。
 * 新判据：只有库里仍是 IN_PROGRESS 才落 CANCELLED，改到 0 行整条放弃。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentTimeoutSchedulerTest {

    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentTaskLineMapper taskLineRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private SessionService sessionService;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private ScheduledTaskService scheduledTaskService;

    private ReplenishmentTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReplenishmentTimeoutScheduler(taskRepository, taskLineRepository, routeRepository,
                sessionService, opsExceptionService, scheduledTaskService);
        when(scheduledTaskService.tryBegin(anyString(), anyLong())).thenReturn(true);
    }

    @Test
    void expire_whenTaskMovedOnConcurrently_skipsCloseAndAlert() {
        ReplenishmentTask snapshot = staleSnapshot(7L);
        when(taskRepository.findByStatusAndCheckInAtBefore(eq("IN_PROGRESS"), any(Instant.class), eq(100)))
                .thenReturn(List.of(snapshot));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(7L)).thenReturn(List.of());
        // 条件更新命中 0 行：扫描之后任务已被完成/取消
        when(taskRepository.cancelIfStatus(eq(7L), eq("IN_PROGRESS"), anyString())).thenReturn(0);

        scheduler.expireStaleCheckedInTasks();

        verify(sessionService, never()).closeRestockSessionsForTask(anyLong(), anyString());
        verify(opsExceptionService, never()).report(anyString(), anyString(), any(), anyString(), anyString());
        verify(taskRepository, never()).save(any());
        verify(routeRepository, never()).save(any());
        // 被并发抢先的不算「本次取消」，计数保持 0
        verify(scheduledTaskService).finish(eq("replenishment-timeout"), eq("SUCCESS"),
                contains("本次无超时"), anyLong());
    }

    @Test
    void expire_whenStillInProgress_cancelsAndClosesSessions() {
        ReplenishmentTask snapshot = staleSnapshot(8L);
        when(taskRepository.findByStatusAndCheckInAtBefore(eq("IN_PROGRESS"), any(Instant.class), eq(100)))
                .thenReturn(List.of(snapshot));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(8L)).thenReturn(List.of());
        when(taskRepository.cancelIfStatus(eq(8L), eq("IN_PROGRESS"), anyString())).thenReturn(1);

        scheduler.expireStaleCheckedInTasks();

        verify(taskRepository).cancelIfStatus(eq(8L), eq("IN_PROGRESS"), contains("签到超过4小时"));
        verify(sessionService).closeRestockSessionsForTask(eq(8L), anyString());
        verify(opsExceptionService).report(eq("RESTOCK_TASK_TIMEOUT"), eq("MEDIUM"), any(), anyString(), anyString());
        verify(scheduledTaskService).finish(eq("replenishment-timeout"), eq("SUCCESS"),
                contains("1 条"), anyLong());
    }

    /** 有已上架明细 ⇒ 告警升级 HIGH（货可能已经上柜，取消不等于没发生）。 */
    @Test
    void expire_withAppliedLines_reportsHigh() {
        ReplenishmentTask snapshot = staleSnapshot(9L);
        when(taskRepository.findByStatusAndCheckInAtBefore(eq("IN_PROGRESS"), any(Instant.class), eq(100)))
                .thenReturn(List.of(snapshot));
        ReplenishmentTaskLine applied = new ReplenishmentTaskLine();
        applied.setApplied(true);
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(9L)).thenReturn(List.of(applied));
        when(taskRepository.cancelIfStatus(eq(9L), eq("IN_PROGRESS"), anyString())).thenReturn(1);

        scheduler.expireStaleCheckedInTasks();

        verify(opsExceptionService).report(eq("RESTOCK_TASK_TIMEOUT"), eq("HIGH"), any(),
                anyString(), contains("请核对库存"));
    }

    private static ReplenishmentTask staleSnapshot(Long taskId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId("CAB-001");
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.now().minusSeconds(5 * 3600));
        return task;
    }
}
