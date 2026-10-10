package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.JiangyiOrderVideo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * §4.2.14 幂等 upsert 单测。
 * <p><b>坑（2026-10-10 实证）</b>：Mockito 对接口 mock 默认<b>不执行 default 方法体</b>
 * （upsertReport 被拦截直接返回 false，真实逻辑根本没跑）。必须
 * {@code withSettings().defaultAnswer(CALLS_REAL_METHODS)} 让 default 方法真实执行；
 * 打桩用 {@code doReturn().when()}——{@code when(mock.x())} 写法在打桩瞬间会先执行
 * real 方法（BaseMapper.selectOne default 实现内部又调 selectList，链式出 null）。</p>
 */
class JiangyiOrderVideoMapperTest {

    private final JiangyiOrderVideoMapper mapper = Mockito.mock(JiangyiOrderVideoMapper.class,
            withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    private final Instant now = Instant.now();

    @Test
    void firstReportInsertsNewRow() {
        doReturn(null).when(mapper).selectOne(any());
        doReturn(1).when(mapper).insert(any(JiangyiOrderVideo.class));

        boolean inserted = mapper.upsertReport("ORD123", "D1", 1, 2,
                List.of("http://oss/a.mp4", "http://oss/b.mp4"), now);

        assertTrue(inserted);
        verify(mapper).insert(Mockito.argThat(row ->
                "ORD123".equals(row.getOrderNo())
                        && "D1".equals(row.getDeviceId())
                        && row.getSerialNum() == 1
                        && row.getVideoQuantity() == 2
                        // 上/下摄像头同片：逗号拼接原文
                        && "http://oss/a.mp4,http://oss/b.mp4".equals(row.getVideoUrls())
                        && row.getReportedAt().equals(now)));
    }

    @Test
    void retryReportUpdatesExistingRow() {
        // uk(order_no, serial_num) 冲突 → 整行覆盖（设备重试以最后一次为准）
        JiangyiOrderVideo existing = new JiangyiOrderVideo();
        existing.setId(7L);
        existing.setOrderNo("ORD123");
        existing.setSerialNum(1);
        doReturn(existing).when(mapper).selectOne(any());
        doReturn(1).when(mapper).updateById(any(JiangyiOrderVideo.class));

        boolean inserted = mapper.upsertReport("ORD123", "D1", 1, 2,
                List.of("http://oss/retry.mp4"), now);

        assertFalse(inserted);
        verify(mapper).updateById(Mockito.argThat(row ->
                row.getId() == 7L && "http://oss/retry.mp4".equals(row.getVideoUrls())));
    }

    @Test
    void emptyUrlListKeepsFailureMarker() {
        // 文档 §4.2.14 明示 [] = 该片生成/上传失败——空串落库留痕
        doReturn(null).when(mapper).selectOne(any());
        doReturn(1).when(mapper).insert(any(JiangyiOrderVideo.class));

        mapper.upsertReport("ORD123", "D1", 2, 2, List.of(), now);

        verify(mapper).insert(Mockito.argThat(row -> "".equals(row.getVideoUrls())));
    }

    @Test
    void nullUrlListTreatedAsEmpty() {
        doReturn(null).when(mapper).selectOne(any());
        doReturn(1).when(mapper).insert(any(JiangyiOrderVideo.class));

        mapper.upsertReport("ORD123", "D1", 1, 1, null, now);

        verify(mapper).insert(Mockito.argThat(row -> "".equals(row.getVideoUrls())));
    }
}
