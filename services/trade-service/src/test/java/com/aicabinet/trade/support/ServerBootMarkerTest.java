package com.aicabinet.trade.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集群会话纪元（2026-10-27 拍板 B）：纪元存 Redis，进程重启不得换纪元——否则 7 天 refresh 宽限期
 * 每次发版被静默吃掉；键被删即重建 ⇒ 「删键=全员下线」旋钮保留且即时生效。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class ServerBootMarkerTest {

    private static final long STORED_EPOCH = 1_728_800_000_000L;

    @Mock private RedissonClient redisson;

    private RBucket<String> bucket;
    private ServerBootMarker marker;

    @BeforeEach
    void setUp() {
        bucket = mock(RBucket.class);
        lenient().when(redisson.<String>getBucket(eq(ServerBootMarker.EPOCH_KEY), any())).thenReturn(bucket);
        marker = new ServerBootMarker(redisson);
    }

    /** 🔴 B 档主判据：Redis 里已有纪元 ⇒ 新进程只复用、不自造，重启与多副本共用同一纪元。 */
    @Test
    void epochMillis_reusesStoredEpochInsteadOfProcessStart() {
        when(bucket.get()).thenReturn(String.valueOf(STORED_EPOCH));

        assertEquals(STORED_EPOCH, marker.epochMillis());
        verify(bucket, never()).trySet(any());
    }

    /** 键缺失（全新环境/运维删键）⇒ 以 SETNX 写入当下时刻。 */
    @Test
    void epochMillis_initializesWithSetNxWhenKeyMissing() {
        when(bucket.get()).thenReturn(null);
        when(bucket.trySet(anyString())).thenReturn(true);

        long epoch = marker.epochMillis();

        assertTrue(epoch > 0L, String.valueOf(epoch));
        verify(bucket).trySet(String.valueOf(epoch));
    }

    /** 并发补键只有一个赢家；输家必须读回赢家纪元，否则两个副本各自定值 ⇒ 跨副本恒 401。 */
    @Test
    void epochMillis_adoptsWinnerEpochOnSetNxRace() {
        when(bucket.get()).thenReturn(null).thenReturn(String.valueOf(STORED_EPOCH));
        when(bucket.trySet(anyString())).thenReturn(false);

        assertEquals(STORED_EPOCH, marker.epochMillis());
    }

    /** 全员下线旋钮：删键后**下一次**校验就重建出新纪元（每次直读，所以即时而非等缓存窗口）。 */
    @Test
    void epochMillis_rebuildsEpochRightAfterKeyDeleted() {
        when(bucket.get()).thenReturn(String.valueOf(STORED_EPOCH));
        assertEquals(STORED_EPOCH, marker.epochMillis());

        when(bucket.get()).thenReturn(null);
        when(bucket.trySet(anyString())).thenReturn(true);
        long afterReset = marker.epochMillis();

        assertNotEquals(STORED_EPOCH, afterReset);
    }

    /** 脏值按「没有纪元」处理并重建，而不是把 parseLong 异常抛进鉴权链。 */
    @Test
    void epochMillis_treatsGarbageStoredValueAsMissing() {
        when(bucket.get()).thenReturn("not-a-number");
        when(bucket.trySet(anyString())).thenReturn(true);

        long epoch = marker.epochMillis();

        assertTrue(epoch > 0L, String.valueOf(epoch));
        verify(bucket).trySet(String.valueOf(epoch));
    }
}
