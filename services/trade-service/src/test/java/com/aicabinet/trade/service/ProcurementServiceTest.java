package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PurchaseOrder;
import com.aicabinet.trade.domain.PurchaseOrderLine;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcurementServiceTest {

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

    private ProcurementService service;

    @BeforeEach
    void setUp() {
        service = new ProcurementService(permissionService, supplierRepository,
                purchaseOrderRepository, purchaseOrderLineRepository, purchaseReturnRepository,
                purchaseReturnLineRepository, warehouseRepository, skuCatalogRepository,
                warehouseService, supplierPayableService);
    }

    @Test
    void getPurchaseOrder_shouldReturnDtoWithLines() {
        PurchaseOrder order = new PurchaseOrder();
        order.setSupplierId("SUP-001");
        order.setWarehouseId("WH-001");
        order.setStatus("RECEIVED");

        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setSkuId("SKU-A");
        line.setBatchNo("B1");
        line.setOrderedQty(10);
        line.setReceivedQty(10);
        line.setUnitCostCents(120);
        line.setExpiryDate(LocalDate.now().plusDays(60));

        when(purchaseOrderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(any()))
                .thenReturn(List.of(line));

        var dto = service.getPurchaseOrder(1L, 1L);

        assertEquals("SUP-001", dto.supplierId());
        assertEquals("WH-001", dto.warehouseId());
        assertEquals("RECEIVED", dto.status());
        assertEquals(1, dto.lines().size());
        assertEquals("SKU-A", dto.lines().get(0).skuId());
        assertEquals(10, dto.lines().get(0).receivedQty());
        verify(permissionService).requirePermission(1L, "ops:procurement:list");
    }

    @Test
    void getPurchaseOrder_shouldRejectUnknown() {
        when(purchaseOrderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.getPurchaseOrder(1L, 99L));
    }
}
