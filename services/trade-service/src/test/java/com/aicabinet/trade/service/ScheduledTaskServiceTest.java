package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduledTaskServiceTest {

    private final ScheduledTaskMapper mapper = mock(ScheduledTaskMapper.class);
    private final ScheduledTaskRegistry registry = mock(ScheduledTaskRegistry.class);
    private final DistributedLockService locks = mock(DistributedLockService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);

    private ScheduledTaskService service(boolean xxlEnabled) {
        when(registry.get(anyString())).thenReturn(java.util.Optional.empty());
        return new ScheduledTaskService(mapper, registry, locks, audit, xxlEnabled);
    }

    @Test
    void setRemark_trimsAndAudits() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("unpaid-cancel");
        row.setTaskName("未付订单自动取消");
        when(locks.tryLock(ScheduledTaskService.scheduledTaskAdminLockKey("unpaid-cancel"), 60, 5))
                .thenReturn(true);
        when(mapper.findByIdForUpdate("unpaid-cancel")).thenReturn(java.util.Optional.of(row));

        ScheduledTaskDto dto = service(false).setRemark(100L, "unpaid-cancel", " 超时关单并回滚库存 ");

        assertEquals("超时关单并回滚库存", dto.remark());
        verify(audit).appendLog(eq(100L), eq("SCHEDULED_TASK_REMARK"),
                eq("SCHEDULED_TASK"), eq("unpaid-cancel"), anyString());
        verify(locks).unlock(ScheduledTaskService.scheduledTaskAdminLockKey("unpaid-cancel"));
    }

    @Test
    void tryBegin_skipsDisabledTaskWithoutLock() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("x");
        row.setEnabled(false);
        when(mapper.selectById("x")).thenReturn(row);

        assertFalse(service(false).tryBegin("x", 600));
        verifyNoInteractions(locks);
    }

    @Test
    void tryBegin_acquiresLockWhenEnabled() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("x");
        row.setEnabled(true);
        when(mapper.selectById("x")).thenReturn(row);
        when(locks.tryLock("job:x", 600, 0)).thenReturn(true);

        assertTrue(service(false).tryBegin("x", 600));
    }

    @Test
    void tryBegin_yieldsManagedTaskWhenXxlEnabled() {
        assertFalse(service(true).tryBegin("unpaid-cancel", 600));
        verifyNoInteractions(locks);
    }

    @Test
    void tryBegin_allowsManagedTaskWhenForcedBuiltin() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("unpaid-cancel");
        row.setEnabled(true);
        when(mapper.selectById("unpaid-cancel")).thenReturn(row);
        when(locks.tryLock("job:unpaid-cancel", 600, 0)).thenReturn(true);

        ScheduledTaskService svc = service(true);
        boolean[] began = {false};
        svc.runAllowingBuiltin(() -> began[0] = svc.tryBegin("unpaid-cancel", 600));

        assertTrue(began[0]);
    }

    @Test
    void tryBegin_stillRunsUnmanagedWhenXxlEnabled() {
        // 业务任务全量托管后，清单外只剩超期看护（Spring 常驻）—— 它必须不受 XXL 开关影响，
        // 否则调度中心故障时它会与被看护任务同时停跑，等于拆掉唯一的报警器。
        String key = ScheduledTaskStaleMonitor.TASK_KEY;
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey(key);
        row.setEnabled(true);
        when(mapper.selectById(key)).thenReturn(row);
        when(locks.tryLock("job:" + key, 600, 0)).thenReturn(true);

        assertTrue(service(true).tryBegin(key, 600));
    }

    @Test
    void finish_writesProvidedResultMessage() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("coupon-expire");
        when(mapper.findByIdForUpdate("coupon-expire")).thenReturn(java.util.Optional.of(row));

        service(false).finish("coupon-expire", "SUCCESS", "过期优惠券 3 张", System.nanoTime());

        assertEquals("SUCCESS", row.getLastResult());
        assertEquals("过期优惠券 3 张", row.getLastMessage());
        verify(mapper).save(row);
        verify(locks).unlock("job:coupon-expire");
    }

    /**
     * F1（收敛计划 2026-11-17）：跳过**不得**推进 last_run_at。
     * 看护的唯一判据就是它 —— 一旦跳过也刷新，「让位给 XXL 但调度中心从不派发」
     * 这种全量停摆就永远显示「刚跑过」，2026-09 那次 19 小时停摆正是这个形态。
     */
    @Test
    void markSkipped_doesNotAdvanceLastRunAt() {
        java.time.Instant lastRealRun = java.time.Instant.parse("2026-11-01T00:00:00Z");
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("reconciliation");
        row.setLastRunAt(lastRealRun);
        when(mapper.findByIdForUpdate("reconciliation")).thenReturn(java.util.Optional.of(row));

        service(false).markSkipped("reconciliation", "skipped: builtin scheduler yields to xxl-job");

        assertEquals(lastRealRun, row.getLastRunAt(), "跳过不是执行，last_run_at 必须保持上次真执行的时间");
        assertEquals("SKIPPED", row.getLastResult());
        assertTrue(row.getLastMessage().contains("yields to xxl-job"));
        verify(mapper).save(row);
    }

    /** 从未跑过的任务被跳过时，last_run_at 必须保持 null —— 否则看护的 NEVER_RUN 判据永远看不见它。 */
    @Test
    void markSkipped_keepsNeverRunRowsVisible() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("settlement-bill");
        when(mapper.findByIdForUpdate("settlement-bill")).thenReturn(java.util.Optional.of(row));

        service(false).markSkipped("settlement-bill", "skipped: builtin scheduler yields to xxl-job");

        assertNull(row.getLastRunAt(), "从未执行过的任务被跳过后仍应保持「无执行记录」，看护才能报 NEVER_RUN");
        assertEquals("SKIPPED", row.getLastResult());
    }

    /**
     * F1：心跳续租的前置判据三态。替一个已终止的线程续租，等于把锁钉死到续租上限，
     * 全集群在该任务上静默空转 —— 这比「锁自然过期后被接管」糟糕得多。
     */
    @Test
    void renewalGuard_distinguishesFlagGoneFromDeadThread() {
        Thread current = Thread.currentThread();
        assertEquals(ScheduledTaskService.RenewalGuard.CONTINUE,
                ScheduledTaskService.inspectRenewalGuard(current, current.getId()));
        assertEquals(ScheduledTaskService.RenewalGuard.STOP_FLAG_GONE,
                ScheduledTaskService.inspectRenewalGuard(null, current.getId()));

        Thread other = new Thread(() -> { }, "other-holder");
        assertEquals(ScheduledTaskService.RenewalGuard.STOP_FLAG_GONE,
                ScheduledTaskService.inspectRenewalGuard(other, current.getId()));

        Thread dead = new Thread(() -> { }, "dead-holder");
        dead.start();
        try {
            dead.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待线程结束被中断", e);
        }
        assertEquals(ScheduledTaskService.RenewalGuard.STOP_THREAD_DEAD,
                ScheduledTaskService.inspectRenewalGuard(dead, dead.getId()));
    }
}
