package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSettlementOverviewDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ProfitSharingStatusDto;
import com.aicabinet.common.dto.RevenueSplitDto;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.dto.OrderVideoPlaylistDto;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantSettlementBillMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.storage.MinioVideoService;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantFinanceServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock CabinetOrderMapper orderRepository;
    @Mock CabinetOrderLineMapper orderLineRepository;
    @Mock OrderRevenueSplitMapper splitRepository;
    @Mock MerchantMapper merchantRepository;
    @Mock SettlementService settlementService;
    @Mock WeChatProfitSharingService profitSharingService;
    @Mock ProfitSharingProperties profitSharingProperties;
    @Mock WeChatPayProperties weChatPayProperties;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock MinioVideoService minioVideoService;
    @Mock JiangyiOrderVideoService jiangyiOrderVideoService;
    @Mock MerchantSettlementBillMapper settlementBillRepository;

    private MerchantFinanceService service;

    @BeforeEach
    void setUp() {
        service = new MerchantFinanceService(
                permissionService, merchantFeaturePackService, merchantPortalGuard,
                orderRepository, orderLineRepository, splitRepository, merchantRepository,
                settlementService, profitSharingService, profitSharingProperties, weChatPayProperties,
                sessionRepository, minioVideoService, jiangyiOrderVideoService,
                new com.aicabinet.trade.service.view.OrderViewAssembler(), settlementBillRepository, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void getSettlementOverview_whenBizPackEmpty_returnsZerosWithoutSplitQuery() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of());
        when(profitSharingProperties.enabled()).thenReturn(false);
        when(profitSharingProperties.retryEnabled()).thenReturn(false);
        when(profitSharingProperties.retryBatchSize()).thenReturn(10);
        when(profitSharingService.isApiReady()).thenReturn(false);
        when(profitSharingService.isMockMode()).thenReturn(true);
        when(weChatPayProperties.isConfigured()).thenReturn(false);

        MerchantSettlementOverviewDto overview = service.getSettlementOverview(9L);

        assertEquals(0, overview.pendingAmountCents());
        assertEquals(0, overview.pendingSplitCount());
        assertEquals(0, overview.settledMonthCents());
        assertEquals(0, overview.failedSplitCount());
        assertTrue(overview.recentFailures().isEmpty());
        ProfitSharingStatusDto ps = overview.profitSharing();
        assertNotNull(ps);
        assertTrue(ps.note().contains("记账"));
        verify(splitRepository, never()).sumMerchantCentsByMerchantIdInAndStatusIn(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listSplits_whenBizPackEmpty_returnsEmptyPage() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of());

        PageResult<RevenueSplitDto> page = service.listSplits(9L, 0, 20, null, null, null);

        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
        verify(splitRepository, never()).searchByMerchants(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ---------- CB-030 订单购物视频清单 ----------

    private CabinetOrder paidOrder(String sessionId) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("ORD-1");
        order.setSessionId(sessionId);
        order.setDeviceId("500909625160");
        order.setMerchantId("MCH-DEFAULT");
        return order;
    }

    @Test
    void orderVideos_whenJiangyiLedgerHasClips_returnsPresignedListWithChannel() {
        when(orderRepository.findById("ORD-1")).thenReturn(java.util.Optional.of(paidOrder("SESS-1")));
        when(sessionRepository.findById("SESS-1")).thenReturn(java.util.Optional.of(new ShoppingSession()));
        // 协议：上下摄像头同片上报 → 同一 serialNum 两个 channel，必须都带出来，否则前端两个同名「第 1 段」
        when(jiangyiOrderVideoService.playByOrderNo("SESS-1")).thenReturn(java.util.List.of(
                new JiangyiOrderVideoService.PlayUrlView(7L, "SESS-1", "500909625160", 1, 1,
                        java.time.Instant.parse("2026-10-10T12:00:00Z"), java.util.List.of(
                                new JiangyiOrderVideoService.PlayUrlItem(1, "https://signed-up", true, true, null),
                                new JiangyiOrderVideoService.PlayUrlItem(2, null, false, false, "该片生成或上传失败")))));

        OrderVideoPlaylistDto playlist = service.orderVideos(9L, "ORD-1");

        assertEquals(OrderVideoPlaylistDto.SOURCE_JIANGYI, playlist.source());
        assertEquals(2, playlist.clips().size());
        OrderVideoPlaylistDto.Clip first = playlist.clips().get(0);
        assertEquals(1, first.serialNum());
        assertEquals(1, first.total());
        assertEquals(1, first.channel());
        assertEquals("https://signed-up", first.url());
        assertTrue(first.playable());
        OrderVideoPlaylistDto.Clip second = playlist.clips().get(1);
        assertEquals(2, second.channel());
        assertEquals("该片生成或上传失败", second.reason());
        org.junit.jupiter.api.Assertions.assertFalse(second.playable());
    }

    @Test
    void orderVideos_whenSessionHasEdgeVideoUri_returnsEdgeWithoutLeakingJiangyiQuery() {
        ShoppingSession session = new ShoppingSession();
        session.setVideoUri("minio://bucket/session/SESS-2.mp4");
        when(orderRepository.findById("ORD-1")).thenReturn(java.util.Optional.of(paidOrder("SESS-2")));
        when(sessionRepository.findById("SESS-2")).thenReturn(java.util.Optional.of(session));

        OrderVideoPlaylistDto playlist = service.orderVideos(9L, "ORD-1");

        assertEquals(OrderVideoPlaylistDto.SOURCE_EDGE, playlist.source());
        assertTrue(playlist.clips().isEmpty());
        verify(jiangyiOrderVideoService, never()).playByOrderNo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void orderVideos_whenNoSourceAtAll_returnsNone() {
        when(orderRepository.findById("ORD-1")).thenReturn(java.util.Optional.of(paidOrder("SESS-3")));
        when(sessionRepository.findById("SESS-3")).thenReturn(java.util.Optional.of(new ShoppingSession()));
        when(jiangyiOrderVideoService.playByOrderNo("SESS-3")).thenReturn(java.util.List.of());

        OrderVideoPlaylistDto playlist = service.orderVideos(9L, "ORD-1");

        assertEquals(OrderVideoPlaylistDto.SOURCE_NONE, playlist.source());
        assertTrue(playlist.clips().isEmpty());
    }

    @Test
    void orderVideos_whenOrderMissing_throws404() {
        when(orderRepository.findById("NOPE")).thenReturn(java.util.Optional.empty());

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.orderVideos(9L, "NOPE"));

        assertEquals(404, ex.getStatusCode().value());
    }
}
