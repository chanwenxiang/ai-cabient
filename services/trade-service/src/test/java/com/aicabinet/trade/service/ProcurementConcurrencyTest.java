package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReceivePurchaseOrderRequest;
import com.aicabinet.trade.mapper.PurchaseOrderLineMapper;
import com.aicabinet.trade.mapper.PurchaseOrderMapper;
import com.aicabinet.trade.mapper.PurchaseReturnLineMapper;
import com.aicabinet.trade.mapper.PurchaseReturnMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SupplierMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcurementConcurrencyTest {

    @Mock private PermissionService permissionService;
    @Mock private SupplierMapper supplierRepository;
    @Mock private PurchaseOrderMapper purchaseOrderRepository;
    @Mock private PurchaseOrderLineMapper purchaseOrderLineRepository;
    @Mock private PurchaseReturnMapper purchaseReturnRepository;
    @Mock private PurchaseReturnLineMapper purchaseReturnLineRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private SupplierPayableService supplierPayableService;
    @Mock private DistributedLockService distributedLockService;

    private ProcurementService service;

    @BeforeEach
    void setUp() {
        service = new ProcurementService(permissionService, supplierRepository,
                purchaseOrderRepository, purchaseOrderLineRepository, purchaseReturnRepository,
                purchaseReturnLineRepository, warehouseRepository, skuCatalogRepository,
                warehouseService, supplierPayableService, distributedLockService);
    }

    @Test
    void receivePurchaseOrder_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(ProcurementService.purchaseOrderLockKey(42L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.receivePurchaseOrder(1L, 42L, new ReceivePurchaseOrderRequest(null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void receivePurchaseOrder_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(ProcurementService.purchaseOrderLockKey(42L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(purchaseOrderRepository.findByIdForUpdate(42L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.receivePurchaseOrder(1L, 42L, new ReceivePurchaseOrderRequest(null, null, null)));

        verify(distributedLockService).unlock(ProcurementService.purchaseOrderLockKey(42L));
    }
}
