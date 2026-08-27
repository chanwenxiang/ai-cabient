package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        ScheduledTaskDto dto = service(false).setRemark(100L, "unpaid-cancel", " 超时关单并回滚库�?");

        assertEquals("超时关单并回滚库�?, dto.remark());
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
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("device-presence");
        row.setEnabled(true);
        when(mapper.selectById("device-presence")).thenReturn(row);
        when(locks.tryLock("job:device-presence", 600, 0)).thenReturn(true);

        assertTrue(service(true).tryBegin("device-presence", 600));
    }

    @Test
    void finish_writesProvidedResultMessage() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("coupon-expire");
        when(mapper.findByIdForUpdate("coupon-expire")).thenReturn(java.util.Optional.of(row));

        service(false).finish("coupon-expire", "SUCCESS", "过期优惠�?3 �?, System.nanoTime());

        assertEquals("SUCCESS", row.getLastResult());
        assertEquals("过期优惠�?3 �?, row.getLastMessage());
        verify(mapper).save(row);
        verify(locks).unlock("job:coupon-expire");
    }
}
