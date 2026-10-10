package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.JiangyiOrderVideo;
import com.aicabinet.trade.mapper.JiangyiOrderVideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CB-029 视频复核读路径：逐片地址必须如实反映三种状态（已签名 / 非本桶原样 / 不可播放），
 * 不能把「签名失败」静默成空串—— 复核页要看得见失败片。
 */
class JiangyiOrderVideoServiceTest {

    private JiangyiOrderVideoMapper mapper;
    private JiangyiGatewayClient gatewayClient;
    private JiangyiOrderVideoService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(JiangyiOrderVideoMapper.class);
        gatewayClient = Mockito.mock(JiangyiGatewayClient.class);
        service = new JiangyiOrderVideoService(mapper, gatewayClient);
    }

    private JiangyiOrderVideo row(String videoUrls) {
        JiangyiOrderVideo row = new JiangyiOrderVideo();
        row.setId(7L);
        row.setOrderNo("ORD1");
        row.setDeviceId("100000000001");
        row.setSerialNum(1);
        row.setVideoQuantity(1);
        row.setVideoUrls(videoUrls);
        row.setReportedAt(Instant.parse("2026-10-10T12:00:00Z"));
        return row;
    }

    @Test
    void ownBucketRefIsSigned() {
        Mockito.when(mapper.selectById(7L)).thenReturn(row(
                "https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/a.mp4"));
        Mockito.when(gatewayClient.presignGet("https://ai-cabinet-by.oss-cn-shenzhen.aliyuncs.com/jiangyi-video/a.mp4"))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.signed("https://signed-a", "2026-10-10T13:20:00Z")));

        JiangyiOrderVideoService.PlayUrlView view = service.playUrls(7L);

        assertEquals("ORD1", view.orderNo());
        assertEquals(1, view.items().size());
        JiangyiOrderVideoService.PlayUrlItem item = view.items().get(0);
        assertEquals(1, item.index());
        assertEquals("https://signed-a", item.url());
        assertTrue(item.signed());
        assertTrue(item.playable());
        assertNull(item.reason());
    }

    @Test
    void externalRefIsPassedThroughUnsigned() {
        String external = "https://cdn.example.com/x.mp4";
        Mockito.when(mapper.selectById(7L)).thenReturn(row(external));
        Mockito.when(gatewayClient.presignGet(external))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.externalRef()));

        JiangyiOrderVideoService.PlayUrlItem item = service.playUrls(7L).items().get(0);

        assertFalse(item.signed());
        assertTrue(item.playable());
        assertEquals(external, item.url());
    }

    @Test
    void signedFailureIsReportedNotSilentlyDropped() {
        Mockito.when(mapper.selectById(7L)).thenReturn(row("jiangyi-video/a.mp4"));
        Mockito.when(gatewayClient.presignGet("jiangyi-video/a.mp4")).thenReturn(Optional.empty());

        JiangyiOrderVideoService.PlayUrlItem item = service.playUrls(7L).items().get(0);

        assertFalse(item.playable());
        assertNull(item.url());
        assertEquals("播放地址签发失败", item.reason());
    }

    @Test
    void emptyVideoUrlsMeansFailedShard() {
        // 协议 §4.2.14：[] = 该片生成或上传失败；不能返回「零片」让复核页看不出异常
        Mockito.when(mapper.selectById(7L)).thenReturn(row(""));

        JiangyiOrderVideoService.PlayUrlItem item = service.playUrls(7L).items().get(0);

        assertEquals(1, item.index());
        assertFalse(item.playable());
        assertEquals("该片生成或上传失败", item.reason());
        Mockito.verify(gatewayClient, Mockito.never()).presignGet(Mockito.anyString());
    }

    @Test
    void middleFailedShardKeepsItsPosition() {
        Mockito.when(mapper.selectById(7L)).thenReturn(row("jiangyi-video/a.mp4,,https://cdn.example.com/c.mp4"));
        Mockito.when(gatewayClient.presignGet("jiangyi-video/a.mp4"))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.signed("https://signed-a", "e")));
        Mockito.when(gatewayClient.presignGet("https://cdn.example.com/c.mp4"))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.externalRef()));

        var items = service.playUrls(7L).items();

        assertEquals(3, items.size());
        assertTrue(items.get(0).playable());
        assertFalse(items.get(1).playable());
        assertEquals("该片生成或上传失败", items.get(1).reason());
        assertTrue(items.get(2).playable());
        assertEquals("https://cdn.example.com/c.mp4", items.get(2).url());
    }

    @Test
    void trailingFailedShardIsNotSilentlyDropped() {
        // 🔴 回归：写侧是 String.join(",", videoUrls)，末尾失败片会留下**尾随逗号**；
        // 若读侧用 String.split(",")（不带 limit），Java 会丢掉末尾空串 →
        // 「最后一路摄像头/最后一片失败」在复核清单里凭空消失。必须用 split(",", -1) 往返对称。
        String joined = String.join(",", List.of("jiangyi-video/a.mp4", ""));
        assertEquals("jiangyi-video/a.mp4,", joined, "写侧 join 必须原样保留末尾空项（前置契约）");
        Mockito.when(mapper.selectById(7L)).thenReturn(row(joined));
        Mockito.when(gatewayClient.presignGet("jiangyi-video/a.mp4"))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.signed("https://signed-a", "e")));

        var items = service.playUrls(7L).items();

        assertEquals(2, items.size(), "末尾失败片不能消失");
        assertTrue(items.get(0).playable());
        assertFalse(items.get(1).playable());
        assertEquals(2, items.get(1).index());
        assertEquals("该片生成或上传失败", items.get(1).reason());
    }

    @Test
    void missingRecordIsRejected() {
        Mockito.when(mapper.selectById(404L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.playUrls(404L));
    }

    @Test
    void playByOrderNoReturnsAllShards() {
        // CB-030：商户端按订单号取全片并逐片签名，片序沿用 mapper 的 serial 升序
        JiangyiOrderVideo second = row("jiangyi-video/b.mp4");
        second.setId(8L);
        second.setSerialNum(2);
        second.setVideoQuantity(2);
        Mockito.when(mapper.findByOrderNo("ORD1")).thenReturn(List.of(row("jiangyi-video/a.mp4"), second));
        Mockito.when(gatewayClient.presignGet("jiangyi-video/a.mp4"))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.signed("https://signed-a", "e")));
        Mockito.when(gatewayClient.presignGet("jiangyi-video/b.mp4"))
                .thenReturn(Optional.of(JiangyiGatewayClient.PresignTarget.signed("https://signed-b", "e")));

        var views = service.playByOrderNo("ORD1");

        assertEquals(2, views.size());
        assertEquals(1, views.get(0).serialNum());
        assertEquals("https://signed-a", views.get(0).items().get(0).url());
        assertEquals(2, views.get(1).serialNum());
        assertEquals("https://signed-b", views.get(1).items().get(0).url());
    }

    @Test
    void playByOrderNoBlankSkipsQuery() {
        assertTrue(service.playByOrderNo("  ").isEmpty());
        assertTrue(service.playByOrderNo(null).isEmpty());
        Mockito.verify(mapper, Mockito.never()).findByOrderNo(Mockito.anyString());
    }
}
