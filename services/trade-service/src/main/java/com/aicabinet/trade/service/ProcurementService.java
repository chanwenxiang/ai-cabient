package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ProcurementService {

    private final PermissionService permissionService;
    private final SupplierMapper supplierRepository;
    private final PurchaseOrderMapper purchaseOrderRepository;
    private final PurchaseOrderLineMapper purchaseOrderLineRepository;
    private final WarehouseMapper warehouseRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final WarehouseService warehouseService;

    public ProcurementService(PermissionService permissionService,
                              SupplierMapper supplierRepository,
                              PurchaseOrderMapper purchaseOrderRepository,
                              PurchaseOrderLineMapper purchaseOrderLineRepository,
                              WarehouseMapper warehouseRepository,
                              SkuCatalogMapper skuCatalogRepository,
                              WarehouseService warehouseService) {
        this.permissionService = permissionService;
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.warehouseRepository = warehouseRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.warehouseService = warehouseService;
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> listSuppliers(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return supplierRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toSupplierDto).toList();
    }

    @Transactional
    public SupplierDto upsertSupplier(Long operatorId, SupplierDto request) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        if (request.supplierId() == null || request.supplierId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplierId required");
        }
        Supplier supplier = supplierRepository.findById(request.supplierId().trim()).orElse(new Supplier());
        supplier.setSupplierId(request.supplierId().trim());
        supplier.setSupplierName(required(request.supplierName(), "supplierName"));
        supplier.setContactName(trimToNull(request.contactName()));
        supplier.setContactPhone(trimToNull(request.contactPhone()));
        supplier.setStatus(request.status() != null && !request.status().isBlank()
                ? request.status().trim().toUpperCase() : "ACTIVE");
        return toSupplierDto(supplierRepository.save(supplier));
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> listPurchaseOrders(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:replenishment:list");
        return purchaseOrderRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toPurchaseDto).toList();
    }

    @Transactional
    public PurchaseOrderDto createPurchaseOrder(Long operatorId, CreatePurchaseOrderRequest request) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        Supplier supplier = supplierRepository.findById(required(request.supplierId(), "supplierId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplier not found"));
        if (!"ACTIVE".equalsIgnoreCase(supplier.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "supplier inactive");
        }
        String warehouseId = request.warehouseId() != null && !request.warehouseId().isBlank()
                ? request.warehouseId().trim() : WarehouseService.DEFAULT_WAREHOUSE_ID;
        if (!warehouseRepository.existsById(warehouseId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "warehouse not found");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchase lines required");
        }

        PurchaseOrder order = new PurchaseOrder();
        order.setSupplierId(supplier.getSupplierId());
        order.setWarehouseId(warehouseId);
        order.setRefNo(trimToNull(request.refNo()));
        order.setNotes(trimToNull(request.notes()));
        order.setOperatorId(operatorId);
        order = purchaseOrderRepository.save(order);

        for (PurchaseOrderLineDto lineDto : request.lines()) {
            validatePurchaseLine(lineDto, false);
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrderId(order.getPurchaseOrderId());
            line.setSkuId(lineDto.skuId().trim());
            line.setBatchNo(lineDto.batchNo().trim());
            line.setProductionDate(lineDto.productionDate());
            line.setExpiryDate(lineDto.expiryDate());
            line.setOrderedQty(lineDto.orderedQty());
            line.setReceivedQty(0);
            line.setUnitCostCents(lineDto.unitCostCents());
            purchaseOrderLineRepository.save(line);
        }
        return toPurchaseDto(order);
    }

    @Transactional
    public PurchaseOrderDto receivePurchaseOrder(Long operatorId, Long purchaseOrderId,
                                                 ReceivePurchaseOrderRequest request) {
        permissionService.requirePermission(operatorId, "ops:replenishment:edit");
        PurchaseOrder order = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "purchase order not found"));
        if (!"CREATED".equals(order.getStatus()) && !"PARTIAL_RECEIVED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "purchase order state invalid");
        }
        List<PurchaseOrderLine> existing = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderByLineIdAsc(purchaseOrderId);
        List<PurchaseOrderLineDto> received = request.lines() != null && !request.lines().isEmpty()
                ? request.lines()
                : existing.stream().map(this::toPurchaseLineDto).toList();

        for (PurchaseOrderLineDto receiveLine : received) {
            PurchaseOrderLine line = matchLine(existing, receiveLine);
            int qty = receiveLine.receivedQty() > 0 ? receiveLine.receivedQty() : line.getOrderedQty();
            if (qty <= 0 || qty > line.getOrderedQty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid receive qty for sku=" + line.getSkuId());
            }
            if (qty <= line.getReceivedQty()) {
                continue;
            }
            int deltaQty = qty - line.getReceivedQty();
            QualityResult quality = inspectPurchaseLine(line, deltaQty);
            if (!quality.accepted()) {
                line.setQualityStatus("REJECTED");
                line.setQualityNote(quality.note());
                line.setRejectedQty(deltaQty);
                purchaseOrderLineRepository.save(line);
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "purchase quality rejected sku=" + line.getSkuId() + " reason=" + quality.note());
            }
            line.setReceivedQty(qty);
            line.setQualityStatus("PASSED");
            line.setQualityNote(quality.note());
            line.setRejectedQty(0);
            purchaseOrderLineRepository.save(line);
            warehouseService.receivePurchaseStock(
                    order.getWarehouseId(),
                    line.getSkuId(),
                    line.getBatchNo(),
                    line.getProductionDate(),
                    line.getExpiryDate(),
                    deltaQty,
                    line.getUnitCostCents(),
                    operatorId,
                    "PURCHASE_ORDER",
                    String.valueOf(order.getPurchaseOrderId())
            );
        }
        boolean allReceived = purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(purchaseOrderId)
                .stream()
                .allMatch(line -> line.getReceivedQty() >= line.getOrderedQty());
        order.setStatus(allReceived ? "RECEIVED" : "PARTIAL_RECEIVED");
        if (allReceived) {
            order.setReceivedAt(Instant.now());
        }
        if (request.notes() != null && !request.notes().isBlank()) {
            order.setNotes(request.notes().trim());
        }
        return toPurchaseDto(purchaseOrderRepository.save(order));
    }

    private PurchaseOrderLine matchLine(List<PurchaseOrderLine> existing, PurchaseOrderLineDto dto) {
        if (dto.lineId() != null) {
            return existing.stream()
                    .filter(l -> dto.lineId().equals(l.getLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchase line not found"));
        }
        return existing.stream()
                .filter(l -> l.getSkuId().equals(dto.skuId()) && l.getBatchNo().equals(dto.batchNo()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchase line not found"));
    }

    private void validatePurchaseLine(PurchaseOrderLineDto dto, boolean receiving) {
        if (dto.skuId() == null || dto.skuId().isBlank()) throw bad("skuId required");
        if (!skuCatalogRepository.existsById(dto.skuId().trim())) throw bad("sku not found: " + dto.skuId());
        if (dto.batchNo() == null || dto.batchNo().isBlank()) throw bad("batchNo required");
        if (dto.expiryDate() == null) throw bad("expiryDate required");
        if (dto.productionDate() != null && dto.productionDate().isAfter(dto.expiryDate())) {
            throw bad("productionDate cannot be after expiryDate");
        }
        if (!dto.expiryDate().isAfter(LocalDate.now())) throw bad("expiryDate must be in future");
        if (!receiving && dto.orderedQty() <= 0) throw bad("orderedQty must be positive");
        if (dto.unitCostCents() <= 0) throw bad("unitCostCents must be positive");
    }

    private QualityResult inspectPurchaseLine(PurchaseOrderLine line, int receiveQty) {
        LocalDate today = LocalDate.now();
        if (line.getExpiryDate() == null) {
            return new QualityResult(false, "EXPIRY_REQUIRED");
        }
        if (!line.getExpiryDate().isAfter(today)) {
            return new QualityResult(false, "EXPIRED");
        }
        if (line.getProductionDate() != null && line.getProductionDate().isAfter(line.getExpiryDate())) {
            return new QualityResult(false, "INVALID_DATE_RANGE");
        }
        if (line.getExpiryDate().isBefore(today.plusDays(7))) {
            return new QualityResult(false, "SHELF_LIFE_TOO_SHORT");
        }
        if (line.getUnitCostCents() <= 0) {
            return new QualityResult(false, "UNIT_COST_REQUIRED");
        }
        if (receiveQty < line.getOrderedQty()) {
            return new QualityResult(true, "PARTIAL_RECEIVE");
        }
        return new QualityResult(true, "OK");
    }

    private record QualityResult(boolean accepted, String note) {}

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private PurchaseOrderDto toPurchaseDto(PurchaseOrder order) {
        return new PurchaseOrderDto(
                order.getPurchaseOrderId(),
                order.getSupplierId(),
                order.getWarehouseId(),
                order.getStatus(),
                order.getRefNo(),
                order.getOperatorId(),
                order.getNotes(),
                order.getCreatedAt(),
                order.getReceivedAt(),
                purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(order.getPurchaseOrderId())
                        .stream().map(this::toPurchaseLineDto).toList()
        );
    }

    private PurchaseOrderLineDto toPurchaseLineDto(PurchaseOrderLine line) {
        return new PurchaseOrderLineDto(
                line.getLineId(), line.getSkuId(), line.getBatchNo(),
                line.getProductionDate(), line.getExpiryDate(),
                line.getOrderedQty(), line.getReceivedQty(), line.getUnitCostCents()
        );
    }

    private SupplierDto toSupplierDto(Supplier supplier) {
        return new SupplierDto(
                supplier.getSupplierId(), supplier.getSupplierName(), supplier.getContactName(),
                supplier.getContactPhone(), supplier.getStatus(), supplier.getCreatedAt()
        );
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " required");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
