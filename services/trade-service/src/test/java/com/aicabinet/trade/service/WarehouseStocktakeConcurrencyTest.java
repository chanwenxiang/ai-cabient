package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import com.aicabinet.trade.client.VisionServiceClient;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseStocktakeConcurrencyTest {

    @Mock private PermissionService permissionService;
    @Mock private WarehouseStocktakeMapper stocktakeRepository;
    @Mock private WarehouseStocktakeLineMapper lineRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private WarehouseInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private VisionServiceClient visionServiceClient;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseStocktakeService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseStocktakeService(permissionService, stocktakeRepository,
                lineRepository, warehouseRepository, inventoryRepository, skuCatalogRepository,
                warehouseService, visionServiceClient, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void complete_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                WarehouseStocktakeService.stocktakeLockKey(3L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.complete(1L, 3L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void complete_whenStocktakeNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                WarehouseStocktakeService.stocktakeLockKey(3L), 60L, 5L))
                .thenReturn(true);
        when(stocktakeRepository.findByIdForUpdate(3L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.complete(1L, 3L));

        verify(distributedLockService).unlock(WarehouseStocktakeService.stocktakeLockKey(3L));
    }
}
