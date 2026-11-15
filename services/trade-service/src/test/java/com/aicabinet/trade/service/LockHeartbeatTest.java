package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MA-274：订单支付锁心跳——租约/3 周期续租、LOST 停心跳、stop 后不再续。
 * 用秒级参数（lease=1s，period=0.05s）实跑真实调度线程。
 */
class LockHeartbeatTest {

    @Test
    void heartbeat_renewsPeriodically_untilStopped() throws Exception {
        DistributedLockService lockService = Mockito.mock(DistributedLockService.class);
        when(lockService.renew(anyString(), eq(1L), anyLong()))
                .thenReturn(DistributedLockService.RenewOutcome.RENEWED);
        LockHeartbeat heartbeat = new LockHeartbeat(lockService, 1, 0);

        java.util.concurrent.ScheduledFuture<?> future = heartbeat.start("k-1", 42L);
        Thread.sleep(250);
        heartbeat.stop(future);
        int afterStop = Mockito.mockingDetails(lockService).getInvocations().size();
        Thread.sleep(150);
        int settled = Mockito.mockingDetails(lockService).getInvocations().size();

        assertTrue(afterStop >= 3, "0.25s 内至少 3 次续租，实际 " + afterStop);
        assertTrue(settled <= afterStop + 1, "stop 后不应再续租（停前至多多 1 次在途）");
    }

    @Test
    void heartbeat_onLockLost_stopsRenewing() throws Exception {
        DistributedLockService lockService = Mockito.mock(DistributedLockService.class);
        AtomicInteger calls = new AtomicInteger();
        when(lockService.renew(anyString(), eq(1L), anyLong()))
                .thenAnswer(inv -> calls.incrementAndGet() >= 2
                        ? DistributedLockService.RenewOutcome.LOST
                        : DistributedLockService.RenewOutcome.RENEWED);
        LockHeartbeat heartbeat = new LockHeartbeat(lockService, 1, 0);

        java.util.concurrent.ScheduledFuture<?> future = heartbeat.start("k-2", 42L);
        Thread.sleep(300);
        heartbeat.stop(future);

        assertTrue(calls.get() < 6, "LOST 后调度异常吞掉本任务 ⇒ 续租次数应封顶，实际 " + calls.get());
    }
}
