package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSettlementOverviewDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ProfitSharingStatusDto;
import com.aicabinet.common.dto.RevenueSplitDto;
import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
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

    private MerchantFinanceService service;

    @BeforeEach
    void setUp() {
        service = new MerchantFinanceService(
                permissionService, merchantFeaturePackService, merchantPortalGuard,
                orderRepository, orderLineRepository, splitRepository, merchantRepository,
                settlementService, profitSharingService, profitSharingProperties, weChatPayProperties,
                sessionRepository, minioVideoService,
                new com.aicabinet.trade.service.view.OrderViewAssembler(), null);
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
}
