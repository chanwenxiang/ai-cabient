package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PurchaseOrderLineDto;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private AdminAuditService auditService;

    private ProcurementService service;

    @BeforeEach
    void setUp() {
        service = new ProcurementService(permissionService, supplierRepository,
                purchaseOrderRepository, purchaseOrderLineRepository, purchaseReturnRepository,
                purchaseReturnLineRepository, warehouseRepository, skuCatalogRepository,
                warehouseService, supplierPayableService, distributedLockService,
                approvalWorkflowService, auditService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        org.mockito.Mockito.lenient()
                .when(skuCatalogRepository.existsById(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        org.mockito.Mockito.lenient()
                .when(distributedLockService.tryLock(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
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

    private PurchaseOrderLineDto lineDto(String skuId, String batchNo, java.time.LocalDate expiry, int qty) {
        return new PurchaseOrderLineDto(null, skuId, batchNo, null, expiry, qty, 0, 120, 0);
    }

    private void stubCreateHappyPath() {
        com.aicabinet.trade.domain.Supplier supplier = new com.aicabinet.trade.domain.Supplier();
        supplier.setSupplierId("SUP-1");
        supplier.setStatus("ACTIVE");
        when(supplierRepository.findById("SUP-1")).thenReturn(java.util.Optional.of(supplier));
        when(warehouseRepository.existsById("WH-1")).thenReturn(true);
        when(skuCatalogRepository.existsById(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(purchaseOrderRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(purchaseOrderLineRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrderLine.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPurchaseOrder_withoutBatch_succeedsDeferredToReceive() {
        stubCreateHappyPath();
        var request = new com.aicabinet.common.dto.CreatePurchaseOrderRequest(
                "SUP-1", "WH-1", null, null, List.of(lineDto("SKU-A", null, null, 10)));

        var dto = service.createPurchaseOrder(1L, request);

        assertEquals("PENDING_APPROVAL", dto.status());
        org.mockito.ArgumentCaptor<PurchaseOrderLine> captor =
                org.mockito.ArgumentCaptor.forClass(PurchaseOrderLine.class);
        verify(purchaseOrderLineRepository).save(captor.capture());
        assertEquals(null, captor.getValue().getBatchNo(), "P1-1：下单批次选填应落 null");
        verify(approvalWorkflowService).start(
                org.mockito.ArgumentMatchers.eq("PURCHASE_ORDER"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void receivePurchaseOrder_withoutBatch_rejectedWithChineseMessage() {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(1L);
        order.setSupplierId("SUP-1");
        order.setWarehouseId("WH-1");
        order.setStatus("CREATED");
        when(purchaseOrderRepository.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(order));
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setLineId(11L);
        line.setPurchaseOrderId(1L);
        line.setSkuId("SKU-A");
        line.setOrderedQty(10);
        line.setReceivedQty(0);
        line.setUnitCostCents(120);
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(1L))
                .thenReturn(List.of(line));

        var request = new com.aicabinet.common.dto.ReceivePurchaseOrderRequest(
                List.of(lineDto("SKU-A", null, null, 10)), null);

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.receivePurchaseOrder(1L, 1L, request));
        assertTrue(String.valueOf(ex.getReason()).contains("批次号必填"),
                "P1-1：下单未填批次时收货必须要求补录");
    }

    @Test
    void receivePurchaseOrder_batchOverride_persistsAndUsesOverrideForLot() {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(1L);
        order.setSupplierId("SUP-1");
        order.setWarehouseId("WH-1");
        order.setStatus("CREATED");
        when(purchaseOrderRepository.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(order));
        when(purchaseOrderRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setLineId(11L);
        line.setPurchaseOrderId(1L);
        line.setSkuId("SKU-A");
        line.setBatchNo("OLD-EST");
        line.setOrderedQty(10);
        line.setReceivedQty(0);
        line.setUnitCostCents(120);
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(1L))
                .thenReturn(List.of(line));
        when(purchaseOrderLineRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrderLine.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new com.aicabinet.common.dto.ReceivePurchaseOrderRequest(
                List.of(lineDto("SKU-A", "REAL-B1", java.time.LocalDate.now().plusDays(90), 10)), null);

        service.receivePurchaseOrder(1L, 1L, request);

        assertEquals("REAL-B1", line.getBatchNo(), "覆盖批次必须回写订单行");
        var lotCaptor = org.mockito.ArgumentCaptor.forClass(
                WarehouseService.PurchaseReceiveCommand.class);
        verify(warehouseService).receivePurchaseStock(lotCaptor.capture());
        assertEquals("REAL-B1", lotCaptor.getValue().lot().batchNo(), "仓批必须使用覆盖后的批次");
    }

    @Test
    void receivePurchaseOrder_bySkuOnlyFallback_whenNoLineIdAndNoBatch() {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(1L);
        order.setSupplierId("SUP-1");
        order.setWarehouseId("WH-1");
        order.setStatus("CREATED");
        when(purchaseOrderRepository.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(order));
        when(purchaseOrderRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setLineId(11L);
        line.setSkuId("SKU-A");
        line.setBatchNo("B-KEEP");
        line.setExpiryDate(java.time.LocalDate.now().plusDays(90));
        line.setOrderedQty(10);
        line.setReceivedQty(0);
        line.setUnitCostCents(120);
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(1L))
                .thenReturn(List.of(line));
        when(purchaseOrderLineRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrderLine.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 不带 lineId、不带批次（沿用订单行 B-KEEP）⇒ sku 兜底命中单行
        service.receivePurchaseOrder(1L, 1L,
                new com.aicabinet.common.dto.ReceivePurchaseOrderRequest(
                        List.of(lineDto("SKU-A", null, null, 10)), null));

        assertEquals("B-KEEP", line.getBatchNo());
        verify(warehouseService).receivePurchaseStock(org.mockito.ArgumentMatchers.any(
                WarehouseService.PurchaseReceiveCommand.class));
    }

    private PurchaseOrder pendingOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(9L);
        order.setSupplierId("SUP-1");
        order.setWarehouseId("WH-1");
        order.setStatus("PENDING_APPROVAL");
        return order;
    }

    @Test
    void reviewPurchaseOrder_withoutFlowDefinition_singleStepToCreated() {
        PurchaseOrder order = pendingOrder();
        when(purchaseOrderRepository.findByIdForUpdate(9L)).thenReturn(java.util.Optional.of(order));
        when(purchaseOrderRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // P2-4：库未配置 PURCHASE_ORDER 审批流（isDefinitionEnabled=false）
        when(approvalWorkflowService.isDefinitionEnabled("PURCHASE_ORDER")).thenReturn(false);

        var dto = service.reviewPurchaseOrder(1L, 9L, true, null);

        assertEquals("CREATED", dto.status(), "无审批流配置必须单步直过，不得永久卡待审批");
        verify(approvalWorkflowService).completeApproved(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("PURCHASE_ORDER"),
                org.mockito.ArgumentMatchers.eq("9"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void reviewPurchaseOrder_withFlowButNotAllNodes_staysPending() {
        PurchaseOrder order = pendingOrder();
        when(purchaseOrderRepository.findByIdForUpdate(9L)).thenReturn(java.util.Optional.of(order));
        when(purchaseOrderRepository.save(org.mockito.ArgumentMatchers.any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.isDefinitionEnabled("PURCHASE_ORDER")).thenReturn(true);
        when(approvalWorkflowService.isInstanceApproved("PURCHASE_ORDER", "9")).thenReturn(false);

        var dto = service.reviewPurchaseOrder(1L, 9L, true, null);

        assertEquals("PENDING_APPROVAL", dto.status(), "多节点链未走完不得提前 CREATED");
    }
}
