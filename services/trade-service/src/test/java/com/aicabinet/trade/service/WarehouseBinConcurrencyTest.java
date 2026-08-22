package com.aicabinet.trade.service;

import com.aicabinet.common.dto.BinMoveRequest;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseBinMapper;
import com.aicabinet.trade.mapper.WarehouseBinStockMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseBinConcurrencyTest {

    @Mock private PermissionService permissionService;
    @Mock private WarehouseBinMapper binRepository;
    @Mock private WarehouseBinStockMapper binStockRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private DistributedLockService distributedLockService;

    private WarehouseBinService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseBinService(permissionService, binRepository, binStockRepository,
                warehouseRepository, skuCatalogRepository, warehouseService, distributedLockService);
    }

    @Test
    void moveBetweenBins_whenBinStockLockBusy_rejectsWithConflict() {
        com.aicabinet.trade.domain.WarehouseBin from = new com.aicabinet.trade.domain.WarehouseBin();
        from.setBinId(1L);
        from.setWarehouseId("WH-1");
        com.aicabinet.trade.domain.WarehouseBin to = new com.aicabinet.trade.domain.WarehouseBin();
        to.setBinId(2L);
        to.setWarehouseId("WH-1");
        when(binRepository.findById(1L)).thenReturn(java.util.Optional.of(from));
        when(binRepository.findById(2L)).thenReturn(java.util.Optional.of(to));
        when(distributedLockService.tryLock(
                eq(WarehouseBinService.binStockLockKey(1L, "SKU-A", "B1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.moveBetweenBins(1L, new BinMoveRequest(1L, 2L, "SKU-A", "B1", 3)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
