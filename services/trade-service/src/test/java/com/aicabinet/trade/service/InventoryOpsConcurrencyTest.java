package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WriteOffRequest;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryOpsConcurrencyTest {

    @Mock private InventoryLotService lotService;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private InventoryWriteOffMapper writeOffRepository;
    @Mock private MerchantOpsPolicyService opsPolicyService;
    @Mock private DistributedLockService distributedLockService;
    /** V313 仓库侧报损依赖；设备侧用例不会调用它，但构造器需要。 */
    @Mock private WarehouseService warehouseService;
    /** N-06：设备侧报损/盘调的设备范围闸（void 方法，默认放行 = 范围内）。 */
    @Mock private MerchantScopeService merchantScopeService;

    private InventoryOpsService service;

    @BeforeEach
    void setUp() {
        service = new InventoryOpsService(lotService, deviceValidationService, skuCatalogRepository,
                inventoryRepository, writeOffRepository, opsPolicyService, distributedLockService,
                warehouseService, merchantScopeService);
    }

    @Test
    void writeOff_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InventoryService.deviceLockKey("CAB-INV"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(1L, new WriteOffRequest("CAB-INV", null, "SKU1", null, 1, "EXPIRED", null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    /**
     * N-06 反向用例：域受限运营报损**范围外**柜机的库存必须 403，且不得动库存/流水。
     *
     * <p>deviceId 在 body 里，上层拦截器看不见；若此处不闸，任何持报损权限的运营
     * 都能扣掉别家商户的货——账面损失不留痕（流水记的是这台柜机，不是「谁越权」）。
     */
    @Test
    void writeOff_whenDeviceOutOfScope_forbiddenBeforeTouchingStock() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限操作该柜机"))
                .when(merchantScopeService).requireDeviceAccess(1L, "CAB-OUT");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(1L, new WriteOffRequest("CAB-OUT", null, "SKU1", null, 1, "EXPIRED", null, null, null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        org.mockito.Mockito.verifyNoInteractions(distributedLockService);
        org.mockito.Mockito.verifyNoInteractions(inventoryRepository);
        org.mockito.Mockito.verifyNoInteractions(writeOffRepository);
    }

    /** N-06 反向用例：盘调范围外柜机必须 403——盘调直接覆盖库存真值，越权等于改写别家的账。 */
    @Test
    void stocktakeAdjust_whenDeviceOutOfScope_forbiddenBeforeTouchingStock() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限操作该柜机"))
                .when(merchantScopeService).requireDeviceAccess(1L, "CAB-OUT");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.stocktakeAdjust(1L, new com.aicabinet.common.dto.StocktakeAdjustRequest(
                        "CAB-OUT", "SKU-A", 5, null, null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        org.mockito.Mockito.verifyNoInteractions(distributedLockService);
        org.mockito.Mockito.verifyNoInteractions(inventoryRepository);
    }

    @Test
    void stocktakeAdjust_lotLedgerDevice_delegatesSkuCountToLotService() {
        when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(lotService.deviceUsesLotLedger("CAB-LOT")).thenReturn(true);
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(new SkuCatalog()));
        when(lotService.sellableQuantity("CAB-LOT", "SKU-A")).thenReturn(1);

        DeviceSkuInventoryId id = new DeviceSkuInventoryId("CAB-LOT", "SKU-A");
        DeviceSkuInventory updated = new DeviceSkuInventory();
        updated.setId(id);
        updated.setQuantity(5);
        when(inventoryRepository.findById(id)).thenReturn(Optional.empty(), Optional.of(updated));

        DeviceSkuInventory result = service.stocktakeAdjust(1L,
                new com.aicabinet.common.dto.StocktakeAdjustRequest(
                        "CAB-LOT", "SKU-A", 5, null, null, null));

        assertEquals(5, result.getQuantity());
        verify(opsPolicyService).requirePhotoEvidence("CAB-LOT", true, null);
        verify(lotService).stocktakeAdjust(eq("CAB-LOT"), eq("SKU-A"), eq(5), eq(1L), anyString());
    }

    @Test
    void stocktakeAdjust_lotLedgerZeroDelta_syncsAggregateWithoutNewMovement() {
        when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(lotService.deviceUsesLotLedger("CAB-LOT")).thenReturn(true);
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(new SkuCatalog()));
        when(lotService.sellableQuantity("CAB-LOT", "SKU-A")).thenReturn(1);
        DeviceSkuInventoryId id = new DeviceSkuInventoryId("CAB-LOT", "SKU-A");
        DeviceSkuInventory existing = new DeviceSkuInventory();
        existing.setId(id);
        existing.setQuantity(1);
        existing.setVersion(0);
        when(inventoryRepository.findById(id)).thenReturn(Optional.of(existing));

        service.stocktakeAdjust(1L, new com.aicabinet.common.dto.StocktakeAdjustRequest(
                "CAB-LOT", "SKU-A", 1, null, null, 0L));

        verify(lotService).syncAggregateInventory("CAB-LOT", "SKU-A");
        verify(lotService, never()).stocktakeAdjust(anyString(), anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    void stocktakeAdjust_nonLotDevice_notBlockedByGate() {
        org.mockito.Mockito.lenient()
                .when(distributedLockService.tryLock(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(java.util.Optional.empty());

        // 非 lot 设备不被闸门拦截；后续因 sku 不存在走 404（旧路径自洽）
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.stocktakeAdjust(1L, new com.aicabinet.common.dto.StocktakeAdjustRequest(
                        "CAB-OLD", "SKU-A", 5, null, null, null)));
        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
