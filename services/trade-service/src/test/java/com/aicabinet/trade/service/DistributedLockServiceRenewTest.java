package com.aicabinet.trade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R18-7（四端审计 2026-11-12）：{@code renew} 必须三态——
 * <ul>
 *   <li>RENEWED：锁仍是持有者（Lua 返回 &gt;0）；</li>
 *   <li>LOST：锁不存在或已易主（Lua 返回 0）——调用方停心跳；</li>
 *   <li>TRANSIENT：Redis 异常/瞬断——调用方保留心跳下周期再试。</li>
 * </ul>
 * 此前「异常也返回 false」把瞬断与易主并成一路：一次网络抖动即永久停租，
 * 长任务剩余时间无保护、到点可被第二实例接管（双跑）。本单测用 mock Redisson
 * 钉住三路分支的映射契约（不依赖真 Redis，快速稳定）。
 */
class DistributedLockServiceRenewTest {

    private static DistributedLockService serviceReturning(Long luaResult) {
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        try {
            when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                    anyList(), any(Object[].class))).thenReturn(luaResult);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new DistributedLockService(client);
    }

    private static DistributedLockService serviceThrowing(Throwable cause) {
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(StringCodec.INSTANCE)).thenReturn(script);
        try {
            Mockito.doThrow(new RuntimeException("redis down"))
                    .when(script).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                            anyList(), any(Object[].class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new DistributedLockService(client);
    }

    @Test
    @DisplayName("Lua 返回 1 ⇒ RENEWED")
    void positiveLuaResultMeansRenewed() {
        assertEquals(DistributedLockService.RenewOutcome.RENEWED,
                serviceReturning(1L).renew("job:it", 10, 123L));
    }

    @Test
    @DisplayName("Lua 返回 0（锁不存在或已易主）⇒ LOST")
    void zeroLuaResultMeansLost() {
        assertEquals(DistributedLockService.RenewOutcome.LOST,
                serviceReturning(0L).renew("job:it", 10, 123L));
    }

    @Test
    @DisplayName("Lua 返回 null ⇒ LOST（owner 不在，与 0 同判）")
    void nullLuaResultMeansLost() {
        assertEquals(DistributedLockService.RenewOutcome.LOST,
                serviceReturning(null).renew("job:it", 10, 123L));
    }

    @Test
    @DisplayName("Redis 异常 ⇒ TRANSIENT（不再并成 false——一次抖动不得永久停租）")
    void redisExceptionMeansTransient_notLost() {
        assertEquals(DistributedLockService.RenewOutcome.TRANSIENT,
                serviceThrowing(new RuntimeException("timeout")).renew("job:it", 10, 123L));
    }

    @Test
    @DisplayName("leaseSeconds<=0 仍拒绝（参数契约不变）")
    void nonPositiveLeaseRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> serviceReturning(1L).renew("job:it", 0, 123L));
    }
}
