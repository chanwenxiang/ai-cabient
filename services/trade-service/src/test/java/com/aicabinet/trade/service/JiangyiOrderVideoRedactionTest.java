package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiOrderVideo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DC-11（2026-10-19 拍板）：视频台账列表脱敏——对象键 videoUrls 不出网，
 * {@code ops:device:list} 持有者只能拿到片数统计；看视频走 ops:device:video 的 play-url。
 */
class JiangyiOrderVideoRedactionTest {

    @Test
    void countClips_parsesCommaSeparated_ignoresBlankSegments() {
        assertEquals(0, JiangyiOrderVideoService.countClips(null));
        assertEquals(0, JiangyiOrderVideoService.countClips(""));
        assertEquals(0, JiangyiOrderVideoService.countClips(" , ,"));
        assertEquals(3, JiangyiOrderVideoService.countClips("a.mp4,b.mp4,c.mp4"));
        assertEquals(2, JiangyiOrderVideoService.countClips("a.mp4, ,b.mp4"));
    }

    @Test
    void toListItem_computesCountFromUrls() {
        JiangyiOrderVideo row = new JiangyiOrderVideo();
        row.setId(7L);
        row.setOrderNo("O-1");
        row.setDeviceId("EG123456789012");
        row.setSerialNum(1);
        row.setVideoQuantity(3);
        row.setVideoUrls("a.mp4,b.mp4");
        row.setReportedAt(Instant.parse("2026-10-19T00:00:00Z"));

        JiangyiOrderVideoService.VideoListItem item = JiangyiOrderVideoService.toListItem(row);

        assertEquals(7L, item.id());
        assertEquals("O-1", item.orderNo());
        assertEquals(2, item.videoCount());
    }

    @Test
    void videoListItem_fieldsContainNoObjectKey_leakProofByShape() {
        // 结构性钉住：脱敏 DTO 组件里永远不允许出现 videoUrls（防将来「顺手」把原始行换回来）
        Set<String> names = java.util.Arrays.stream(JiangyiOrderVideoService.VideoListItem.class.getDeclaredFields())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("id", "orderNo", "deviceId", "serialNum", "videoQuantity", "videoCount", "reportedAt"),
                names);
        assertTrue(names.stream().noneMatch(n -> n.toLowerCase().contains("url")));
    }
}
