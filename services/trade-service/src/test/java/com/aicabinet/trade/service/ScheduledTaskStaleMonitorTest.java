package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超期看护判据测试。
 *
 * <p>重点不是「能跑通」，而是钉死判据：只看 {@code scheduled_task.last_run_at}，
 * 只覆盖托管清单，恢复后自动关闭异常。</p>
 */
class ScheduledTaskStaleMonitorTest {

    private final ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
    private final ScheduledTaskService taskService = mock(ScheduledTaskService.class);
    private final OpsAlertDispatcher dispatcher = mock(OpsAlertDispatcher.class);
    private final OpsExceptionService exceptionService = mock(OpsExceptionService.class);

    private final Map<String, ScheduledTask> rows = new HashMap<>();

    private ScheduledTaskStaleMonitor monitor;

    @BeforeEach
    void setUp() {
        when(mapper.selectById(anyString())).thenAnswer(inv -> rows.get(inv.getArgument(0, String.class)));
        when(taskService.tryBegin(eq(ScheduledTaskStaleMonitor.TASK_KEY), anyLong())).thenReturn(true);
        monitor = newMonitor(true, 360);
    }

    private ScheduledTaskStaleMonitor newMonitor(boolean enabled, long realertMinutes) {
        return new ScheduledTaskStaleMonitor(mapper, taskService, dispatcher, exceptionService,
                new SimpleMeterRegistry(), enabled, realertMinutes);
    }

    private void healthy(String key) {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey(key);
        row.setTaskName("任务-" + key);
        row.setEnabled(true);
        row.setLastRunAt(Instant.now());
        rows.put(key, row);
    }

    private void allHealthy() {
        XxlJobManagedTasks.KEYS.forEach(this::healthy);
    }

    @Test
    void scan_flagsOverdueTaskByLastRunAt() {
        allHealthy();
        ScheduledTask row = rows.get("unpaid-cancel");
        row.setTaskName("未付订单自动取消");
        row.setLastRunAt(Instant.now().minus(Duration.ofMinutes(60))); // 阈值 45 分钟

        List<ScheduledTaskStaleMonitor.StaleTask> stale = monitor.scan(Instant.now());

        assertEquals(1, stale.size());
        assertEquals("unpaid-cancel", stale.get(0).taskKey());
        assertEquals(ScheduledTaskStaleMonitor.Reason.OVERDUE, stale.get(0).reason());
        assertEquals(Duration.ofMinutes(45), stale.get(0).maxSilence());
    }

    @Test
    void scan_flagsNeverRunAndMissingRow() {
        allHealthy();
        rows.get("reconciliation").setLastRunAt(null);   // 有登记行但从未执行
        rows.remove("coupon-expire");                    // 连登记行都没有

        List<ScheduledTaskStaleMonitor.StaleTask> stale = monitor.scan(Instant.now());

        Map<String, ScheduledTaskStaleMonitor.Reason> byKey = new HashMap<>();
        stale.forEach(s -> byKey.put(s.taskKey(), s.reason()));
        assertEquals(ScheduledTaskStaleMonitor.Reason.NEVER_RUN, byKey.get("reconciliation"));
        assertEquals(ScheduledTaskStaleMonitor.Reason.MISSING_ROW, byKey.get("coupon-expire"));
        assertEquals(2, stale.size());
    }

    @Test
    void scan_skipsDisabledTaskAndNonManagedTask() {
        allHealthy();
        rows.get("unpaid-cancel").setEnabled(false);
        rows.get("unpaid-cancel").setLastRunAt(Instant.now().minus(Duration.ofDays(5)));
        // 非托管任务再久也不算停跑（内置 @Scheduled 不会让位，另有兜底）
        ScheduledTask nonManaged = new ScheduledTask();
        nonManaged.setTaskKey("device-presence");
        nonManaged.setTaskName("设备离线巡检");
        nonManaged.setEnabled(true);
        nonManaged.setLastRunAt(Instant.now().minus(Duration.ofDays(5)));
        rows.put("device-presence", nonManaged);

        assertTrue(monitor.scan(Instant.now()).isEmpty());
    }

    @Test
    void check_raisesExceptionAndAlertsOnceWithinRealertWindow() {
        allHealthy();
        rows.get("recharge-cancel").setLastRunAt(Instant.now().minus(Duration.ofMinutes(90)));

        monitor.check();
        monitor.check();

        verify(exceptionService, times(2)).report(eq(ScheduledTaskStaleMonitor.ALERT_TYPE), eq("CRITICAL"),
                any(), anyString(), anyString());
        // 第 4 参之后的 String... 必须显式按数组匹配，否则 Mockito 会按单元素匹配而判不中
        verify(dispatcher, times(1)).send(eq(ScheduledTaskStaleMonitor.ALERT_TYPE), anyString(),
                anyString(), any(), any(String[].class));
        verify(taskService, times(2)).finish(eq(ScheduledTaskStaleMonitor.TASK_KEY), eq("SUCCESS"),
                anyString(), anyLong());
    }

    @Test
    void check_realertsWhenStaleSetChanges() {
        allHealthy();
        rows.get("recharge-cancel").setLastRunAt(Instant.now().minus(Duration.ofMinutes(90)));

        monitor.check();
        rows.get("recharge-cancel").setLastRunAt(Instant.now());
        rows.get("data-consistency").setLastRunAt(Instant.now().minus(Duration.ofMinutes(90)));
        monitor.check();

        verify(dispatcher, times(2)).send(eq(ScheduledTaskStaleMonitor.ALERT_TYPE), anyString(),
                anyString(), any(), any(String[].class));
    }

    @Test
    void check_autoResolvesWhenAllHealthyAgain() {
        allHealthy();

        monitor.check();

        verify(exceptionService).resolveSystem(ScheduledTaskStaleMonitor.ALERT_TYPE, "GLOBAL",
                "托管任务已恢复按时执行");
        verify(dispatcher, never()).send(anyString(), anyString(), anyString(), any(),
                any(String[].class));
    }

    @Test
    void check_isNoopWhenDisabled() {
        allHealthy();
        newMonitor(false, 360).check();

        verify(taskService, never()).tryBegin(anyString(), anyLong());
    }

    @Test
    void everyManagedTaskHasSilenceThresholdAndMonitorIsNotManaged() {
        XxlJobManagedTasks.KEYS.forEach(key ->
                assertTrue(com.aicabinet.trade.support.ScheduleZones.MAX_SILENCE_BY_TASK.containsKey(key),
                        key + " 缺少超期阈值"));
        assertFalse(XxlJobManagedTasks.isManaged(ScheduledTaskStaleMonitor.TASK_KEY));
    }
}
