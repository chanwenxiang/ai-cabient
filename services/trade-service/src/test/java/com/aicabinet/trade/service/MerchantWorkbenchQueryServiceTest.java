package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWorkbenchDto;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantWorkbenchQueryServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock OrderRevenueSplitMapper splitRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock DisputeTicketMapper disputeRepository;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock PullOffTaskMapper pullOffTaskRepository;
    @Mock DeviceSlotService deviceSlotService;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;

    private MerchantWorkbenchQueryService service;

    @BeforeEach
    void setUp() {
        service = new MerchantWorkbenchQueryService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                deviceRepository, orderRepository, splitRepository, sessionRepository,
                disputeRepository, inventoryRepository, pullOffTaskRepository,
                deviceSlotService, replenishmentTaskRepository);
    }

    @Test
    void g1_pendingSplitStatuses_differFromOpsWorkbench_alignWithMerchantFinance() {
        List<String> merchant = MerchantWorkbenchQueryService.PENDING_SPLIT_STATUSES;
        List<String> ops = OpsWorkbenchQueryService.PENDING_SPLIT_STATUSES;

        assertTrue(merchant.contains("PENDING"));
        assertTrue(merchant.contains("WECHAT_SUBMITTED"));
        assertTrue(merchant.contains("SUBMITTED"));
        assertFalse(merchant.contains("FAILED"));
        assertFalse(merchant.contains("WECHAT_FAILED"));

        assertFalse(ops.contains("PENDING"));
        assertTrue(ops.contains("FAILED") || ops.contains("WECHAT_FAILED")
                || ops.stream().anyMatch(s -> s.contains("FAILED")));
        assertTrue(ops.contains("ACCRUED"));

        // 与商户财务结算 overview 同源口径
        assertEquals(
                List.of("PENDING", "ACCRUED", "LEDGER_ONLY", "WECHAT_SUBMITTED", "SUBMITTED"),
                merchant);
    }

    @Test
    void getWorkbench_whenAllScopesEmpty_returnsZeros() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of());
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of());
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of());

        MerchantWorkbenchDto wb = service.getWorkbench(9L);

        assertEquals(0, wb.openDisputes());
        assertEquals(0, wb.offlineDevices());
        assertEquals(0, wb.lowStockItems());
        assertEquals(0, wb.pendingSplits());
        assertTrue(wb.actionItems().isEmpty());
    }
}
