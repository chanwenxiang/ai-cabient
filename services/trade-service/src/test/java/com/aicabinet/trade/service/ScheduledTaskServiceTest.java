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
    private final DistributedLockService locks = mock(DistributedLockService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);

    private ScheduledTaskService service() {
        return new ScheduledTaskService(mapper, locks, audit);
    }

    @Test
    void setRemark_trimsAndAudits() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("unpaid-cancel");
        row.setTaskName("未付订单自动取消");
        when(mapper.selectById("unpaid-cancel")).thenReturn(row);

        ScheduledTaskDto dto = service().setRemark(100L, "unpaid-cancel", " 超时关单并回滚库存 ");

        assertEquals("超时关单并回滚库存", dto.remark());
        verify(audit).record(eq(100L), eq("SCHEDULED_TASK_REMARK"),
                eq("SCHEDULED_TASK"), eq("unpaid-cancel"), anyString());
    }

    @Test
    void tryBegin_skipsDisabledTaskWithoutLock() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("x");
        row.setEnabled(false);
        when(mapper.selectById("x")).thenReturn(row);

        assertFalse(service().tryBegin("x", 600));
        verifyNoInteractions(locks);
    }

    @Test
    void tryBegin_acquiresLockWhenEnabled() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("x");
        row.setEnabled(true);
        when(mapper.selectById("x")).thenReturn(row);
        when(locks.tryLock("job:x", 600, 0)).thenReturn(true);

        assertTrue(service().tryBegin("x", 600));
    }

    @Test
    void finish_writesProvidedResultMessage() {
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey("coupon-expire");
        when(mapper.selectById("coupon-expire")).thenReturn(row);

        service().finish("coupon-expire", "SUCCESS", "过期优惠券 3 张", System.nanoTime());

        assertEquals("SUCCESS", row.getLastResult());
        assertEquals("过期优惠券 3 张", row.getLastMessage());
        verify(mapper).save(row);
        verify(locks).unlock("job:coupon-expire");
    }
}
