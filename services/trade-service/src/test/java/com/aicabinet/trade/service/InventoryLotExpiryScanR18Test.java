package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.InventoryMovementMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R18-2 收口（2026-11-13）：过期扫描（scanExpiryAlerts）跨设备批量、不在设备锁内，
 * 原状态更新走 {@code save(lot)}（绝对覆盖整个实体含 quantity）——与并发扣减竞争时
 * 会用旧快照覆盖已扣量（账面虚高、可再卖）。修复=状态更新改走
 * {@code updateStatusAndSlotById}（只写 status/slot 不碰 quantity）。
 * 本类钉住：过期/临期两条路径必须调专用 UPDATE，save 永不出现。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryLotExpiryScanR18Test {

    @Mock private DeviceSkuLotMapper lotRepository;
    @Mock private InventoryMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PullOffTaskMapper pullOffTaskRepository;
    @Mock private DeviceSlotMapper slotRepository;
    @Mock private DistributedLockService distributedLockService;

    private InventoryLotService service;

    @BeforeEach
    void setUp() {
        service = new InventoryLotService(lotRepository, movementRepository, inventoryRepository,
                skuCatalogRepository, pullOffTaskRepository, slotRepository, distributedLockService, null);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static DeviceSkuLot lot(String lotId, LocalDate expiryDate, String status) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId(lotId);
        lot.setDeviceId("D-1");
        lot.setSkuId("SKU-1");
        lot.setBatchNo("B-1");
        lot.setExpiryDate(expiryDate);
        lot.setQuantity(10);
        lot.setStatus(status);
        return lot;
    }

    @Test
    void scanExpiry_expiredLot_usesUpdateStatusAndSlotNotSave() {
        DeviceSkuLot expired = lot("L-EXP", LocalDate.now().minusDays(1), "ON_SALE");
        when(lotRepository.findForExpiryScan(any(), any(), any(Integer.class)))
                .thenReturn(List.of(expired));
        when(skuCatalogRepository.findById(anyString())).thenReturn(java.util.Optional.empty());
        when(pullOffTaskRepository.findByLotIdAndStatus(eq("L-EXP"), eq("OPEN")))
                .thenReturn(java.util.Optional.empty());
        when(pullOffTaskRepository.save(any())).thenReturn(null);

        service.scanExpiryAlerts();

        verify(lotRepository).updateStatusAndSlotById(eq("L-EXP"), eq("BLOCKED"), eq(null));
        verify(lotRepository, never()).save(any());
        assertEquals("BLOCKED", expired.getStatus());
    }

    @Test
    void scanExpiry_nearExpiryLot_usesUpdateStatusAndSlotNotSave() {
        DeviceSkuLot near = lot("L-NEAR", LocalDate.now().plusDays(3), "ON_SALE");
        when(lotRepository.findForExpiryScan(any(), any(), any(Integer.class)))
                .thenReturn(List.of(near));
        when(skuCatalogRepository.findById(anyString())).thenReturn(java.util.Optional.empty());
        when(pullOffTaskRepository.findByLotIdAndStatus(eq("L-NEAR"), eq("OPEN")))
                .thenReturn(java.util.Optional.empty());
        when(pullOffTaskRepository.save(any())).thenReturn(null);

        service.scanExpiryAlerts();

        verify(lotRepository).updateStatusAndSlotById(eq("L-NEAR"), eq("NEAR_EXPIRY"), eq(null));
        verify(lotRepository, never()).save(any());
        assertEquals("NEAR_EXPIRY", near.getStatus());
    }
}
