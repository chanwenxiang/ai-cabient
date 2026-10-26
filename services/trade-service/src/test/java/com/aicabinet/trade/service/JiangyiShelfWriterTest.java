package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.common.dto.UpdateDeviceSkuPriceRequest;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 跨柜一键上架的单柜写侧：钱相关口径逐条钉住（只认厂商识别名对应的那一行、不静默改收款对象、
 * 价格走既有改价入口、只补空柜）。
 */
@ExtendWith(MockitoExtension.class)
class JiangyiShelfWriterTest {

    private static final String DEVICE_ID = "100000000001";
    private static final String SKU_ID = "SKU-100017";
    private static final String TEXT_NAME = "雪碧500ml";

    @Mock private JiangyiDeviceMapper deviceMapper;
    @Mock private JiangyiClassMappingMapper mappingMapper;
    @Mock private JiangyiMappingReviewService mappingReviewService;
    @Mock private OpsDevicePricingService devicePricingService;
    @Mock private DeviceSkuPriceMapper priceMapper;
    @Mock private DeviceSkuInventoryMapper inventoryMapper;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private OpsReplenishmentAdminService replenishmentAdminService;
    @Mock private AdminAuditService adminAuditService;

    private JiangyiShelfWriter writer() {
        return new JiangyiShelfWriter(deviceMapper, mappingMapper, mappingReviewService,
                devicePricingService, priceMapper, inventoryMapper, inventoryLotService,
                replenishmentAdminService, adminAuditService);
    }

    private JiangyiDevice boundDevice() {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE_ID);
        d.setStatus("BOUND");
        return d;
    }

    private JiangyiClassMapping mapping(int classId, String textName, String skuId, String status) {
        JiangyiClassMapping m = new JiangyiClassMapping();
        m.setDeviceId(DEVICE_ID);
        m.setClassId(classId);
        m.setTextName(textName);
        m.setSkuId(skuId);
        m.setStatus(status);
        m.setSource("MODEL_SYNC");
        return m;
    }

    private void happyPreconditions() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(true);
        when(mappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(29, TEXT_NAME, null, "DISABLED")));
    }

    @Test
    void shelveOne_writesMappingPriceAndStockOnEmptyNoLotCabinet() {
        happyPreconditions();
        when(priceMapper.findByDeviceIdAndSkuId(DEVICE_ID, SKU_ID)).thenReturn(Optional.empty());
        when(inventoryLotService.deviceUsesLotLedger(DEVICE_ID)).thenReturn(false);
        when(inventoryMapper.findById(any())).thenReturn(Optional.empty());

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, 350, 4);

        assertEquals(JiangyiShelfWriter.STATUS_SHELVED, outcome.status());
        assertEquals(29, outcome.classId());
        assertEquals(350, outcome.priceCents());
        assertEquals(4, outcome.quantity());

        ArgumentCaptor<JiangyiClassMapping> row = ArgumentCaptor.forClass(JiangyiClassMapping.class);
        verify(mappingMapper).upsert(row.capture());
        assertEquals(SKU_ID, row.getValue().getSkuId());
        assertEquals("ACTIVE", row.getValue().getStatus());
        assertEquals(29, row.getValue().getClassId());

        ArgumentCaptor<UpdateDeviceSkuPriceRequest> price =
                ArgumentCaptor.forClass(UpdateDeviceSkuPriceRequest.class);
        verify(devicePricingService).updatePricing(eq(9L), eq(DEVICE_ID), eq(SKU_ID), price.capture());
        assertEquals(350, price.getValue().priceCents());
        assertEquals(Boolean.TRUE, price.getValue().enabled());
        assertNull(price.getValue().expectedVersion());

        ArgumentCaptor<DeviceInventoryDto> stock = ArgumentCaptor.forClass(DeviceInventoryDto.class);
        verify(replenishmentAdminService).upsertInventory(eq(9L), stock.capture());
        assertEquals(4, stock.getValue().quantity());
        assertEquals(4, stock.getValue().capacity());
        assertEquals(2, stock.getValue().lowThreshold());
        assertEquals(0L, stock.getValue().inventoryVersion());

        verify(adminAuditService).appendLog(eq(9L), eq("JIANGYI_SHELF"), eq("jiangyi_device"),
                eq(DEVICE_ID), anyString());
    }

    @Test
    void shelveOne_alreadyShelvedWritesNothingButStillAppliesPrice() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(true);
        when(mappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(29, TEXT_NAME, SKU_ID, "ACTIVE")));
        DeviceSkuPrice existing = new DeviceSkuPrice();
        existing.setDeviceId(DEVICE_ID);
        existing.setSkuId(SKU_ID);
        existing.setVersion(6L);
        when(priceMapper.findByDeviceIdAndSkuId(DEVICE_ID, SKU_ID)).thenReturn(Optional.of(existing));

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, 380, null);

        assertEquals(JiangyiShelfWriter.STATUS_ALREADY, outcome.status());
        assertEquals(29, outcome.classId());
        assertNull(outcome.quantity());
        verify(mappingMapper, never()).upsert(any());
        verify(devicePricingService).updatePricing(eq(9L), eq(DEVICE_ID), eq(SKU_ID),
                eq(new UpdateDeviceSkuPriceRequest(380, true, 6L)));
        verifyNoInteractions(replenishmentAdminService, inventoryMapper, inventoryLotService);
    }

    @Test
    void shelveOne_skipsUnboundDevice() {
        JiangyiDevice pending = new JiangyiDevice();
        pending.setDeviceId(DEVICE_ID);
        pending.setStatus("UNBOUND");
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(pending);

        assertEquals(JiangyiShelfWriter.STATUS_SKIPPED,
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, null).status());
        verifyNoInteractions(mappingMapper, mappingReviewService, devicePricingService,
                replenishmentAdminService, adminAuditService);
    }

    @Test
    void shelveOne_skipsUnverifiedMapping() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(false);

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, 350, 4);

        assertEquals(JiangyiShelfWriter.STATUS_SKIPPED, outcome.status());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.notes().get(0).contains("未按当前模型复核"));
        verifyNoInteractions(mappingMapper, devicePricingService, replenishmentAdminService);
    }

    @Test
    void shelveOne_skipsWhenCabinetModelHasNoSuchTextName() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(true);
        when(mappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(28, "可乐500ml", null, "DISABLED")));

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, null);

        assertEquals(JiangyiShelfWriter.STATUS_SKIPPED, outcome.status());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.notes().get(0).contains("没有识别名"));
        verify(mappingMapper, never()).upsert(any());
    }

    @Test
    void shelveOne_ignoresNegativeSentinelRows() {
        // V400 复核重算腾位行（classId<0）不是覆盖，不能被当成「已挂载」
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(true);
        when(mappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(-29, TEXT_NAME, "SKU-OLD", "DISABLED")));

        assertEquals(JiangyiShelfWriter.STATUS_SKIPPED,
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, null).status());
        verify(mappingMapper, never()).upsert(any());
    }

    @Test
    void shelveOne_skipsDuplicateTextNameRows() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(true);
        when(mappingMapper.listByDevice(DEVICE_ID)).thenReturn(List.of(
                mapping(29, TEXT_NAME, null, "DISABLED"),
                mapping(33, " " + TEXT_NAME + " ", null, "DISABLED")));

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, null);

        assertEquals(JiangyiShelfWriter.STATUS_SKIPPED, outcome.status());
        org.junit.jupiter.api.Assertions.assertTrue(outcome.notes().get(0).contains("冲突"));
        verify(mappingMapper, never()).upsert(any());
    }

    @Test
    void shelveOne_refusesToRepointRowOwnedByAnotherSku() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice());
        when(mappingReviewService.isVerified(DEVICE_ID)).thenReturn(true);
        when(mappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(29, TEXT_NAME, "SKU-OTHER", "ACTIVE")));

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, null);

        assertEquals(JiangyiShelfWriter.STATUS_SKIPPED, outcome.status());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.notes().get(0).contains("已挂其它 SKU"));
        verify(mappingMapper, never()).upsert(any());
        verifyNoInteractions(devicePricingService, replenishmentAdminService);
    }

    @Test
    void shelveOne_skipsStockOnLotLedgerCabinet() {
        happyPreconditions();
        when(inventoryLotService.deviceUsesLotLedger(DEVICE_ID)).thenReturn(true);

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, 4);

        assertEquals(JiangyiShelfWriter.STATUS_SHELVED, outcome.status());
        assertNull(outcome.quantity());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.notes().stream().anyMatch(n -> n.contains("批次账本")));
        verifyNoInteractions(replenishmentAdminService);
    }

    @Test
    void shelveOne_keepsExistingStockUntouched() {
        happyPreconditions();
        when(inventoryLotService.deviceUsesLotLedger(DEVICE_ID)).thenReturn(false);
        DeviceSkuInventory existing = new DeviceSkuInventory();
        existing.setDeviceId(DEVICE_ID);
        existing.setSkuId(SKU_ID);
        existing.setQuantity(3);
        existing.setCapacity(6);
        existing.setVersion(2L);
        when(inventoryMapper.findById(any())).thenReturn(Optional.of(existing));

        JiangyiShelfWriter.WriterOutcome outcome =
                writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, 4);

        assertNull(outcome.quantity());
        org.junit.jupiter.api.Assertions.assertTrue(
                outcome.notes().stream().anyMatch(n -> n.contains("已有库存 3 件")));
        verifyNoInteractions(replenishmentAdminService);
    }

    @Test
    void shelveOne_keepsLargerCapacityWhenFillingEmptyRow() {
        happyPreconditions();
        when(inventoryLotService.deviceUsesLotLedger(DEVICE_ID)).thenReturn(false);
        DeviceSkuInventory existing = new DeviceSkuInventory();
        existing.setDeviceId(DEVICE_ID);
        existing.setSkuId(SKU_ID);
        existing.setQuantity(0);
        existing.setCapacity(8);
        existing.setLowThreshold(3);
        existing.setVersion(5L);
        when(inventoryMapper.findById(any())).thenReturn(Optional.of(existing));

        writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, 2);

        ArgumentCaptor<DeviceInventoryDto> stock = ArgumentCaptor.forClass(DeviceInventoryDto.class);
        verify(replenishmentAdminService).upsertInventory(anyLong(), stock.capture());
        assertEquals(2, stock.getValue().quantity());
        assertEquals(8, stock.getValue().capacity());
        assertEquals(3, stock.getValue().lowThreshold());
        assertEquals(5L, stock.getValue().inventoryVersion());
    }

    @Test
    void shelveOne_withoutPriceOrQuantityTouchesNeither() {
        happyPreconditions();

        writer().shelveOne(9L, DEVICE_ID, SKU_ID, TEXT_NAME, null, null);

        verifyNoInteractions(devicePricingService, replenishmentAdminService, inventoryMapper,
                inventoryLotService, priceMapper);
    }
}
