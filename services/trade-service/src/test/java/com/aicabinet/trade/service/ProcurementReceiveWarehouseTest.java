package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PurchaseOrderLineDto;
import com.aicabinet.common.dto.ReceivePurchaseOrderRequest;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H33：实际收货仓必须回写采购单 warehouseId，保证退货从同一仓扣减。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcurementReceiveWarehouseTest {

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
                warehouseService, supplierPayableService, distributedLockService, null, null, null);
        lenient().when(distributedLockService.tryLock(any(), anyLong(), anyLong())).thenReturn(true);
    }

    private PurchaseOrder order() {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(42L);
        order.setSupplierId("SUP-1");
        order.setWarehouseId("WH-A");
        order.setStatus("CREATED");
        return order;
    }

    private PurchaseOrderLine line() {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setLineId(1L);
        line.setPurchaseOrderId(42L);
        line.setSkuId("SKU-1");
        line.setBatchNo("B1");
        line.setOrderedQty(10);
        line.setReceivedQty(0);
        line.setReturnedQty(0);
        line.setUnitCostCents(120);
        line.setProductionDate(LocalDate.now().minusDays(5));
        line.setExpiryDate(LocalDate.now().plusDays(60));
        return line;
    }

    @Test
    void receiveToDifferentWarehouse_writesBackOrderWarehouse() {
        PurchaseOrder order = order();
        PurchaseOrderLine line = line();
        when(purchaseOrderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(42L))
                .thenAnswer(inv -> {
                    // 同一对象被 processReceiveLine 就地 setReceivedQty(qty)，
                    // finalize 阶段再读即见全量接收；此处不得预置，否则首步即早退
                    return List.of(line);
                });
        when(purchaseOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(
                List.of(new PurchaseOrderLineDto(1L, "SKU-1", "B1", null, null, 10, 10, 120, 0)),
                null,
                "WH-B");

        var dto = service.receivePurchaseOrder(1L, 42L, request);

        // 入库发到实际收货仓 WH-B
        ArgumentCaptor<WarehouseService.PurchaseReceiveCommand> cmd =
                ArgumentCaptor.forClass(WarehouseService.PurchaseReceiveCommand.class);
        verify(warehouseService).receivePurchaseStock(cmd.capture());
        assertEquals("WH-B", cmd.getValue().warehouseId());

        // 订单仓回写为实际收货仓，后续退货同仓扣减
        ArgumentCaptor<PurchaseOrder> saved = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(saved.capture());
        assertEquals("WH-B", saved.getValue().getWarehouseId());
        assertEquals("WH-B", dto.warehouseId());
        assertEquals("RECEIVED", dto.status());
    }

    @Test
    void receiveToOrderWarehouse_keepsWarehouseUnchanged() {
        PurchaseOrder order = order();
        PurchaseOrderLine line = line();
        when(purchaseOrderRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(order));
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(42L))
                .thenAnswer(inv -> {
                    line.setReceivedQty(line.getOrderedQty());
                    return List.of(line);
                });
        when(purchaseOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(
                List.of(new PurchaseOrderLineDto(1L, "SKU-1", "B1", null, null, 10, 10, 120, 0)),
                null,
                null);

        service.receivePurchaseOrder(1L, 42L, request);

        ArgumentCaptor<PurchaseOrder> saved = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderRepository).save(saved.capture());
        assertEquals("WH-A", saved.getValue().getWarehouseId());
    }
}
