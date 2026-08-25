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
import static org.mockito.ArgumentMatchers.eq;
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
                eq(InventoryService.deviceLockKey("CAB-INV")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(1L, new WriteOffRequest("CAB-INV", "SKU1", null, 1, "EXPIRED")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
