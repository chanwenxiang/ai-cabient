package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RateLimitProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiRateLimitServiceTest {

    @Mock private RedissonClient redisson;
    @Mock private RAtomicLong counter;
    @Mock private RAtomicLong deviceCounter;

    private ApiRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new ApiRateLimitService(redisson, new RateLimitProperties(true, 2, 2, 20, 30, 60));
    }

    @Test
    void assertCouponClaimAllowed_whenUnderLimit_increments() {
        when(redisson.getAtomicLong(anyString())).thenReturn(counter);
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);

        service.assertCouponClaimAllowed(10001L);

        verify(counter).expire(java.time.Duration.ofHours(1));
    }

    @Test
    void assertCouponClaimAllowed_whenAtLimit_rejects() {
        when(redisson.getAtomicLong(anyString())).thenReturn(counter);
        when(counter.get()).thenReturn(2L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertCouponClaimAllowed(10001L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals(ApiMessages.TOO_MANY_COUPON_CLAIMS, ex.getReason());
        verify(counter, never()).incrementAndGet();
    }

    @Test
    void assertOrderPayAllowed_whenDisabled_skipsRedis() {
        service = new ApiRateLimitService(redisson, new RateLimitProperties(false, 2, 2, 20, 30, 60));

        service.assertOrderPayAllowed(10001L);

        verify(redisson, never()).getAtomicLong(anyString());
    }

    @Test
    void assertSessionCreateAllowed_whenAtLimit_rejects() {
        when(redisson.getAtomicLong(anyString())).thenReturn(counter);
        when(counter.get()).thenReturn(2L);
        service = new ApiRateLimitService(redisson, new RateLimitProperties(true, 2, 2, 2, 2, 2));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertSessionCreateAllowed(10001L));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals(ApiMessages.TOO_MANY_SESSION_CREATES, ex.getReason());
    }

    @Test
    void assertOpenDoorAllowed_whenUnderLimit_incrementsUserAndDevice() {
        when(redisson.getAtomicLong(eq("aicabinet:rate:open_door:10001"))).thenReturn(counter);
        when(redisson.getAtomicLong(eq("aicabinet:rate:open_door_device:DEV-1"))).thenReturn(deviceCounter);
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);
        when(deviceCounter.get()).thenReturn(0L);
        when(deviceCounter.incrementAndGet()).thenReturn(1L);
        service = new ApiRateLimitService(redisson, new RateLimitProperties(true, 2, 2, 2, 2, 2));

        service.assertOpenDoorAllowed(10001L, "DEV-1");

        verify(counter).expire(java.time.Duration.ofHours(1));
        verify(deviceCounter).expire(java.time.Duration.ofHours(1));
    }

    @Test
    void assertOpenDoorAllowed_whenDeviceAtLimit_rejects() {
        when(redisson.getAtomicLong(eq("aicabinet:rate:open_door:10001"))).thenReturn(counter);
        when(redisson.getAtomicLong(eq("aicabinet:rate:open_door_device:DEV-1"))).thenReturn(deviceCounter);
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);
        when(deviceCounter.get()).thenReturn(2L);
        service = new ApiRateLimitService(redisson, new RateLimitProperties(true, 2, 2, 2, 2, 2));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertOpenDoorAllowed(10001L, "DEV-1"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals(ApiMessages.TOO_MANY_OPENS, ex.getReason());
        verify(deviceCounter, never()).incrementAndGet();
        verify(counter, times(1)).incrementAndGet();
    }

    @Test
    void assertOpenDoorAllowed_whenDeviceIdBlank_onlyUserDimension() {
        when(redisson.getAtomicLong(eq("aicabinet:rate:open_door:10001"))).thenReturn(counter);
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);
        service = new ApiRateLimitService(redisson, new RateLimitProperties(true, 2, 2, 2, 2, 2));

        service.assertOpenDoorAllowed(10001L, "  ");

        verify(redisson, times(1)).getAtomicLong(anyString());
    }
}
