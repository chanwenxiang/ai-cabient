package com.aicabinet.trade.service;

import com.aicabinet.trade.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L04：公开匿名页按 IP 分钟窗口限流。 */
@ExtendWith(MockitoExtension.class)
class ApiRateLimitServiceIpLimitTest {

    private static final RateLimitProperties PROPS =
            new RateLimitProperties(true, 20, 30, 20, 30, 60);

    @Mock private RedissonClient redisson;
    @Mock private RAtomicLong counter;

    private ApiRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new ApiRateLimitService(redisson, PROPS);
        lenient().when(redisson.getAtomicLong(anyString())).thenReturn(counter);
    }

    @Test
    void ipLimit_underLimit_passesAndCounts() {
        when(counter.get()).thenReturn(0L);
        when(counter.incrementAndGet()).thenReturn(1L);

        assertDoesNotThrow(() -> service.assertIpAllowed(
                ApiRateLimitService.ACTION_OPEN_LANDING, "1.2.3.4", 20, "请求过于频繁，请稍后再试"));

        verify(counter).expire(java.time.Duration.ofMinutes(1));
    }

    @Test
    void ipLimit_overLimit_rejectsWith429() {
        when(counter.get()).thenReturn(20L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertIpAllowed(
                        ApiRateLimitService.ACTION_OPEN_LANDING, "1.2.3.4", 20, "请求过于频繁，请稍后再试"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(counter, never()).incrementAndGet();
    }

    @Test
    void ipLimit_blankIpOrDisabled_noOp() {
        assertDoesNotThrow(() -> service.assertIpAllowed(
                ApiRateLimitService.ACTION_OPEN_LANDING, null, 20, "msg"));

        ApiRateLimitService disabled = new ApiRateLimitService(redisson,
                new RateLimitProperties(false, 20, 30, 20, 30, 60));
        assertDoesNotThrow(() -> disabled.assertIpAllowed(
                ApiRateLimitService.ACTION_OPEN_LANDING, "1.2.3.4", 20, "msg"));
    }
}
