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

    static {
        // 纯 Mockito 环境无 SqlSession ⇒ LambdaQueryWrapper 解析列名需要 TableInfo 缓存
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.aicabinet.trade.domain.MerchantFeeLedger.class);
    }

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
    @Mock JiangyiOrderVideoService jiangyiOrderVideoService;
    @Mock MerchantSettlementBillMapper settlementBillRepository;
    @Mock com.aicabinet.trade.mapper.DeviceDataFeeBillMapper dataFeeBillRepository;
    @Mock com.aicabinet.trade.mapper.MerchantFeeLedgerMapper feeLedgerRepository;

    private MerchantFinanceService service;

    @BeforeEach
    void setUp() {
        service = new MerchantFinanceService(
                permissionService, merchantFeaturePackService, merchantPortalGuard,
                orderRepository, orderLineRepository, splitRepository, merchantRepository,
                settlementService, profitSharingService, profitSharingProperties, weChatPayProperties,
                sessionRepository, jiangyiOrderVideoService,
                new com.aicabinet.trade.service.view.OrderViewAssembler(), settlementBillRepository,
                dataFeeBillRepository, feeLedgerRepository, null);
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
    void orderVideos_whenNoSourceAtAll_returnsNone() {
        when(orderRepository.findById("ORD-1")).thenReturn(java.util.Optional.of(paidOrder("SESS-3")));
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

    // ---------- MA-272 月账单流量费必须读真扣台账 ----------

    /**
     * 全量视图：流量费取自 {@code merchant_fee_ledger}（日结批真实扣过的钱）。
     * 修前读 {@code device_data_fee_bill}（月度出账开关默认关闭）⇒ 恒 0 ⇒ 商户利润虚高。
     */
    @Test
    void getMonthlyBill_allDevices_readsDataFeeFromLedger() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("MCH-DEFAULT"));
        when(merchantFeaturePackService.intersectDeviceFilterForPack(9L, null, MerchantFeaturePacks.BIZ))
                .thenReturn(null);
        when(orderLineRepository.merchantBillSalesByDay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(orderLineRepository.merchantBillRefundsByDay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(feeLedgerRepository.sumAmountByMerchantsAndTypeAndMonth(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(12345L);

        var bill = service.getMonthlyBill(9L, "2026-04", null);

        assertEquals(12345L, bill.dataFeeCents(), "流量费必须取自日扣台账，不能是停用出账表的 0");
        assertEquals(-12345L, bill.profitCents(), "无销售/退款/成本时，利润应为负的流量费");
        org.mockito.Mockito.verifyNoInteractions(dataFeeBillRepository);
    }

    /**
     * 单设备视图（B4/MA-299）：只读该柜机的逐台 DATA 行，不拿全商户聚合额顶替。
     * 有逐台行 ⇒ basis=DEVICE；不再读开关默认关闭的 device_data_fee_bill 死表。
     */
    @Test
    void getMonthlyBill_singleDevice_readsPerDeviceLedger() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("MCH-DEFAULT"));
        when(merchantFeaturePackService.intersectDeviceFilterForPack(9L, "DEV-A", MerchantFeaturePacks.BIZ))
                .thenReturn(java.util.List.of("DEV-A"));
        when(orderLineRepository.merchantBillSalesByDay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(orderLineRepository.merchantBillRefundsByDay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        com.aicabinet.trade.domain.MerchantFeeLedger row = new com.aicabinet.trade.domain.MerchantFeeLedger();
        row.setMerchantId("MCH-DEFAULT");
        row.setDeviceId("DEV-A");
        row.setFeeType(com.aicabinet.trade.domain.MerchantFeeLedger.FEE_TYPE_DATA);
        row.setFeeDate(java.time.LocalDate.of(2026, 4, 15));
        row.setAmountCents(777L);
        when(feeLedgerRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(row));

        var bill = service.getMonthlyBill(9L, "2026-04", "DEV-A");

        assertEquals(777L, bill.dataFeeCents(), "单设备流量费必须取该柜机逐台行");
        assertEquals("DEVICE", bill.dataFeeBasis());
        org.mockito.Mockito.verifyNoInteractions(dataFeeBillRepository);
    }

    /**
     * MA-398：单设备无逐台行时的兜底计数必须只数商户级聚合行（device_id IS NULL）。
     * 不加 isNull 时，同商户其它柜机的逐台行会被计入 ⇒ 本该 NONE 的 basis 误判成 MERCHANT。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void getMonthlyBill_singleDevice_fallbackCountExcludesOtherDevices() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("MCH-DEFAULT"));
        when(merchantFeaturePackService.intersectDeviceFilterForPack(9L, "DEV-A", MerchantFeaturePacks.BIZ))
                .thenReturn(java.util.List.of("DEV-A"));
        when(orderLineRepository.merchantBillSalesByDay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(orderLineRepository.merchantBillRefundsByDay(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(feeLedgerRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(feeLedgerRepository.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);

        var bill = service.getMonthlyBill(9L, "2026-04", "DEV-A");

        assertEquals("NONE", bill.dataFeeBasis());
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<com.aicabinet.trade.domain.MerchantFeeLedger>> captor =
                (org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<com.aicabinet.trade.domain.MerchantFeeLedger>>) (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        org.mockito.Mockito.verify(feeLedgerRepository).selectCount(captor.capture());
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper wrapper =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper) captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("device_id IS NULL"),
                "兜底计数必须带 device_id IS NULL，否则其它柜机逐台行会把 basis 误判成 MERCHANT：" + sql);
    }
}
