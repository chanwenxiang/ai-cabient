package com.aicabinet.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * 分布式锁租约与解锁行为单测（BE-003）。
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockServiceTest {

    @Mock RedissonClient redissonClient;
    @Mock RLock rLock;

    DistributedLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new DistributedLockService(redissonClient);
        lenient().when(redissonClient.getLock(startsWith("aicabinet:lock:"))).thenReturn(rLock);
    }

    @Test
    void tryLock_rejectsNonPositiveLease() {
        assertThrows(IllegalArgumentException.class, () -> lockService.tryLock("inv:CAB-001", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> lockService.tryLock("inv:CAB-001", -1));
    }

    @Test
    void tryLock_acquiresWithLease() throws Exception {
        when(rLock.tryLock(1L, 30L, TimeUnit.SECONDS)).thenReturn(true);
        assertTrue(lockService.tryLock("inv:CAB-001", 30, 1));
        verify(rLock).tryLock(1L, 30L, TimeUnit.SECONDS);
    }

    @Test
    void unlock_onlyWhenHeldByCurrentThread() {
        when(rLock.isHeldByCurrentThread()).thenReturn(false);
        lockService.unlock("inv:CAB-001");
        verify(rLock, never()).unlock();

        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        lockService.unlock("inv:CAB-001");
        verify(rLock).unlock();
    }
}
