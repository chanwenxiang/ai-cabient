package com.aicabinet.jiangyi.tracker;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R18-1（四端审计 2026-11-12）回归：网关 OPEN watchdog 阈值必须来自配置，
 * 默认 70s ≥ trade {@code session-expire.opening-seconds}（60s）+ 调度余量。
 * 此前硬编码 15s 抢在 trade 过期处置前把弱网会话置 FAILED，兜账 v2 只救 CANCELLED
 * ——货已拿走但无单可结。本测试钉住「阈值=构造参数」这一契约：改配置必须改变行为
 * （A/B 只改一个变量），防止有人改回硬编码。
 */
class SessionWatchdogTest {

    private static CommandTracker mockTrackerReturning(List<CommandTracker.Pending> pendings) {
        CommandTracker tracker = mock(CommandTracker.class);
        when(tracker.due(eq(CommandTracker.Kind.OPEN), anyLong())).thenReturn(pendings);
        when(tracker.due(eq(CommandTracker.Kind.CLOSE), anyLong())).thenReturn(List.of());
        when(tracker.due(eq(CommandTracker.Kind.DOING), anyLong())).thenReturn(List.of());
        when(tracker.due(eq(CommandTracker.Kind.MODEL), anyLong())).thenReturn(List.of());
        return tracker;
    }

    private static SessionWatchdog newWatchdog(CommandTracker tracker,
                                               TradeInternalClient client,
                                               long openTimeoutMs) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        return new SessionWatchdog(tracker, client, redis, openTimeoutMs);
    }

    @Test
    void defaultThresholdIsSeventySeconds_notLegacyFifteen() {
        CommandTracker tracker = mockTrackerReturning(List.of());
        SessionWatchdog watchdog = newWatchdog(tracker, mock(TradeInternalClient.class), 70_000L);

        watchdog.sweep();

        long before = System.currentTimeMillis();
        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(tracker).due(eq(CommandTracker.Kind.OPEN), cutoff.capture());
        // due 的 cutoff = now - 70000 ⇒ 15~70s 内登记的 pending 不会被本类误杀
        long impliedNow = cutoff.getValue() + 70_000L;
        assertThat(before - impliedNow).isLessThan(5_000L);
    }

    @Test
    void thresholdComesFromConfig_olderThanTimeoutIsReported() {
        CommandTracker.Pending pending = new CommandTracker.Pending(
                "s-1", "dev-1", "id-1", System.currentTimeMillis() - 80_000L);
        CommandTracker tracker = mockTrackerReturning(List.of(pending));
        TradeInternalClient client = mock(TradeInternalClient.class);
        SessionWatchdog watchdog = newWatchdog(tracker, client, 70_000L);

        watchdog.sweep();

        verify(client).postOpenFailed(eq("s-1"), anyString(), eq("dev-1"));
        verify(tracker).evict(CommandTracker.Kind.OPEN, "s-1");
    }

    /**
     * MA-281（2026-11-15 拍板改约）：原「15s 旧行为仍可配」的对照用例与新契约冲突——
     * 低于 70s 地板意味着 watchdog 先于 trade 60s 过期处置生效，弱网真开门被误判 FAILED
     * （兜账只救 CANCELLED）。现在装配期必须失败，而不是静默改小阈值。
     */
    @Test
    void belowFloorThreshold_isRejectedAtConstruction() {
        IllegalArgumentException ignored = null; // 占位防误用
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> newWatchdog(mockTrackerReturning(List.of()), mock(TradeInternalClient.class), 15_000L));
    }

    /** 阈值高于地板仍可配：90s 同样走 due 查询，证明可配性保留在地板之上。 */
    @Test
    void aboveFloorThreshold_remainsConfigurable() {
        CommandTracker tracker = mockTrackerReturning(List.of());
        SessionWatchdog watchdog = newWatchdog(tracker, mock(TradeInternalClient.class), 90_000L);

        watchdog.sweep();

        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(tracker).due(eq(CommandTracker.Kind.OPEN), cutoff.capture());
        long impliedNow = cutoff.getValue() + 90_000L;
        assertThat(System.currentTimeMillis() - impliedNow).isLessThan(5_000L);
    }

    @Test
    void modelTimeoutEvictsPendingSoNextDeploymentCanRegister() {
        CommandTracker.Pending pending = new CommandTracker.Pending(
                "9", "dev-model", "id-model", System.currentTimeMillis() - 700_000L);
        CommandTracker tracker = mockTrackerReturning(List.of());
        when(tracker.due(eq(CommandTracker.Kind.MODEL), anyLong())).thenReturn(List.of(pending));
        TradeInternalClient client = mock(TradeInternalClient.class);
        SessionWatchdog watchdog = newWatchdog(tracker, client, 70_000L);

        watchdog.sweep();

        verify(client).modelPushTimeout(9L, "模型下发10分钟无设备回执");
        verify(tracker).evict(CommandTracker.Kind.MODEL, "9");
    }

    @Test
    void notifyFailureKeepsPendingForNextSweep() {
        CommandTracker.Pending pending = new CommandTracker.Pending(
                "s-2", "dev-2", "id-2", System.currentTimeMillis() - 80_000L);
        CommandTracker tracker = mockTrackerReturning(List.of(pending));
        TradeInternalClient client = mock(TradeInternalClient.class);
        org.mockito.Mockito.doThrow(new RuntimeException("trade down"))
                .when(client).postOpenFailed(anyString(), anyString(), anyString());
        SessionWatchdog watchdog = newWatchdog(tracker, client, 70_000L);

        watchdog.sweep();

        // 上报失败不出队：下一趟再试（原语义保留）
        verify(tracker, org.mockito.Mockito.never()).evict(any(), anyString());
    }
}
