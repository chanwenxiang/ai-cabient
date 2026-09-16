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
        // 默认把「进程启动时刻」推到无限早：等价于「进程一直活着」，即停机豁免不生效。
        // 这样既有断言测的仍是纯 last_run_at 判据；要测停机豁免的用例自己覆写该字段。
        monitor.serviceStart = Instant.EPOCH;
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
        // 非托管任务再久也不算停跑：看护只覆盖 XxlJobManagedTasks.KEYS。
        // 业务任务全量托管后，清单外只剩看护自己（cache-purge 不进注册表，也不在此扫描范围）。
        ScheduledTask nonManaged = new ScheduledTask();
        nonManaged.setTaskKey(ScheduledTaskStaleMonitor.TASK_KEY);
        nonManaged.setTaskName("定时任务超期看护");
        nonManaged.setEnabled(true);
        nonManaged.setLastRunAt(Instant.now().minus(Duration.ofDays(5)));
        rows.put(ScheduledTaskStaleMonitor.TASK_KEY, nonManaged);

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

    // ── 停机豁免：进程没活着的时段不算任务失职（「我关机了怎么跑」）────────────

    @Test
    void scan_exemptsSilenceShorterThanProcessUptime() {
        // 机器关机 3 小时、刚开机 10 分钟：recharge-cancel 阈值 20 分钟，
        // 真实静默 3 小时，但进程只活了 10 分钟 —— 不能判超期。
        allHealthy();
        Instant now = Instant.now();
        rows.get("recharge-cancel").setLastRunAt(now.minus(Duration.ofHours(3)));
        monitor.serviceStart = now.minus(Duration.ofMinutes(10));

        assertTrue(monitor.scan(now).isEmpty(), "停机期的静默不应被判超期");
    }

    @Test
    void scan_stillFlagsOnceUptimeExceedsThreshold() {
        // 同一场景延后：进程已活 30 分钟（> 20 分钟阈值）仍未执行 —— 这才是真停跑。
        // 豁免的是「停机期」，不是「任务」。
        allHealthy();
        Instant now = Instant.now();
        rows.get("recharge-cancel").setLastRunAt(now.minus(Duration.ofHours(3)));
        monitor.serviceStart = now.minus(Duration.ofMinutes(30));

        List<ScheduledTaskStaleMonitor.StaleTask> stale = monitor.scan(now);

        assertEquals(1, stale.size());
        assertEquals("recharge-cancel", stale.get(0).taskKey());
        assertEquals(ScheduledTaskStaleMonitor.Reason.OVERDUE, stale.get(0).reason());
        assertTrue(stale.get(0).afterRestart(), "应标记为「静默起算点被进程启动截断」");
        assertEquals(Duration.ofMinutes(30), stale.get(0).silence(), "静默应从进程启动时刻起算");
    }

    @Test
    void scan_neverRunGetsGracePeriodAfterRestart() {
        allHealthy();
        Instant now = Instant.now();
        rows.get("reconciliation").setLastRunAt(null); // 阈值 26 小时

        monitor.serviceStart = now.minus(Duration.ofMinutes(5));
        assertTrue(monitor.scan(now).isEmpty(), "刚重启就报「无执行记录」没有信息量");

        monitor.serviceStart = now.minus(Duration.ofHours(27));
        List<ScheduledTaskStaleMonitor.StaleTask> stale = monitor.scan(now);
        assertEquals(1, stale.size());
        assertEquals(ScheduledTaskStaleMonitor.Reason.NEVER_RUN, stale.get(0).reason());
    }

    @Test
    void scan_missingRowIsNotExcusedByRestart() {
        // 缺登记行是配置缺陷（执行记录无处落），成因与停机无关 —— 重启不给宽限。
        allHealthy();
        rows.remove("coupon-expire");
        monitor.serviceStart = Instant.now();

        List<ScheduledTaskStaleMonitor.StaleTask> stale = monitor.scan(Instant.now());

        assertEquals(1, stale.size());
        assertEquals(ScheduledTaskStaleMonitor.Reason.MISSING_ROW, stale.get(0).reason());
    }

    @Test
    void everyManagedTaskHasSilenceThresholdAndMonitorIsNotManaged() {
        XxlJobManagedTasks.KEYS.forEach(key ->
                assertTrue(com.aicabinet.trade.support.ScheduleZones.MAX_SILENCE_BY_TASK.containsKey(key),
                        key + " 缺少超期阈值"));
        assertFalse(XxlJobManagedTasks.isManaged(ScheduledTaskStaleMonitor.TASK_KEY));
    }
}
