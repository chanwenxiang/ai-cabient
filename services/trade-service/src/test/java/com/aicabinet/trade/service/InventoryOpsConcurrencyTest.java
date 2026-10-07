package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WriteOffRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private InventoryOpsService service;

    @BeforeEach
    void setUp() {
        service = new InventoryOpsService(lotService, deviceValidationService, skuCatalogRepository,
                inventoryRepository, writeOffRepository, opsPolicyService, distributedLockService);
    }

    @Test
    void writeOff_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InventoryService.deviceLockKey("CAB-INV"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(1L, new WriteOffRequest("CAB-INV", "SKU1", null, 1, "EXPIRED", null, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void stocktakeAdjust_rejectsLotLedgerDeviceWithDirectionToSlotStocktake() {
        org.mockito.Mockito.lenient()
                .when(distributedLockService.tryLock(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(lotService.deviceUsesLotLedger("CAB-LOT")).thenReturn(true);

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.stocktakeAdjust(1L, new com.aicabinet.common.dto.StocktakeAdjustRequest(
                        "CAB-LOT", "SKU-A", 5, null, null, null)));
        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(ex.getReason()).contains("货道盘点"),
                "P1-2：lot 账本设备整机盲调必须被拒并指向货道盘点");
        org.mockito.Mockito.verifyNoInteractions(inventoryRepository);
    }

    @Test
    void stocktakeAdjust_nonLotDevice_notBlockedByGate() {
        org.mockito.Mockito.lenient()
                .when(distributedLockService.tryLock(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(lotService.deviceUsesLotLedger("CAB-OLD")).thenReturn(false);
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
