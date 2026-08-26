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
    private static final String PURCHASE_ORDER_NOT_FOUND = "purchase order not found";
    private static final String PURCHASE_LINE_NOT_FOUND = "purchase line not found";
    private static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String PARTIAL_RECEIVED = "PARTIAL_RECEIVED";


    private final PermissionService permissionService;
    private final SupplierMapper supplierRepository;
    private final PurchaseOrderMapper purchaseOrderRepository;
    private final PurchaseOrderLineMapper purchaseOrderLineRepository;
    private final PurchaseReturnMapper purchaseReturnRepository;
    private final PurchaseReturnLineMapper purchaseReturnLineRepository;
    private final WarehouseMapper warehouseRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final WarehouseService warehouseService;
    private final SupplierPayableService supplierPayableService;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final AdminAuditService auditService;

    private static final String BIZ_PURCHASE_ORDER = "PURCHASE_ORDER";

    public ProcurementService(PermissionService permissionService,
                              SupplierMapper supplierRepository,
                              PurchaseOrderMapper purchaseOrderRepository,
                              PurchaseOrderLineMapper purchaseOrderLineRepository,
                              PurchaseReturnMapper purchaseReturnRepository,
                              PurchaseReturnLineMapper purchaseReturnLineRepository,
                              WarehouseMapper warehouseRepository,
                              SkuCatalogMapper skuCatalogRepository,
                              WarehouseService warehouseService,
                              SupplierPayableService supplierPayableService,
                              DistributedLockService distributedLockService,
                              ApprovalWorkflowService approvalWorkflowService,
                              AdminAuditService auditService) {
        this.permissionService = permissionService;
        this.supplierRepository = supplierRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.purchaseReturnRepository = purchaseReturnRepository;
        this.purchaseReturnLineRepository = purchaseReturnLineRepository;
        this.warehouseRepository = warehouseRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.warehouseService = warehouseService;
        this.supplierPayableService = supplierPayableService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> listSuppliers(Long operatorId) {
        requireWarehouseRead(operatorId);
        return supplierRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toSupplierDto).toList();
    }

    @Transactional
    public SupplierDto upsertSupplier(Long operatorId, SupplierDto request) {
        requireWarehouseWrite(operatorId);
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
        supplier.setPaymentTermsDays(request.paymentTermsDays() != null ? request.paymentTermsDays() : 30);
        supplier.setCreditLimitCents(request.creditLimitCents());
        return toSupplierDto(supplierRepository.save(supplier));
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> listPurchaseOrders(Long operatorId) {
        requireWarehouseRead(operatorId);
        return purchaseOrderRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toPurchaseDto).toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto getPurchaseOrder(Long operatorId, Long purchaseOrderId) {
        requireWarehouseRead(operatorId);
        PurchaseOrder order = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PURCHASE_ORDER_NOT_FOUND));
        return toPurchaseDto(order);
    }

    @Transactional
    public PurchaseOrderDto createPurchaseOrder(Long operatorId, CreatePurchaseOrderRequest request) {
        requireWarehouseWrite(operatorId);
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
        order.setStatus(PENDING_APPROVAL);
        order = purchaseOrderRepository.save(order);
        if (order.getRefNo() == null || order.getRefNo().isBlank()) {
            order.setRefNo("PO-" + order.getPurchaseOrderId());
            order = purchaseOrderRepository.save(order);
        }

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
            line.setReturnedQty(0);
            line.setUnitCostCents(lineDto.unitCostCents());
            purchaseOrderLineRepository.save(line);
        }
        approvalWorkflowService.start(
                BIZ_PURCHASE_ORDER,
                String.valueOf(order.getPurchaseOrderId()),
                operatorId,
                "采购单 " + order.getRefNo());
        return toPurchaseDto(order);
    }

    @Transactional
    public PurchaseOrderDto reviewPurchaseOrder(Long operatorId, Long purchaseOrderId,
                                                boolean approve, String remark) {
        requireWarehouseWrite(operatorId);
        return runWithPurchaseOrderLock(purchaseOrderId,
                () -> doReviewPurchaseOrder(operatorId, purchaseOrderId, approve, remark));
    }

    /** Demo / 内部编排：按当前节点待办人依次通过，直至可收货。 */
    @Transactional
    public void ensurePurchaseOrderApproved(Long operatorId, Long purchaseOrderId) {
        runWithPurchaseOrderLock(purchaseOrderId, () -> {
            for (int i = 0; i < 4; i++) {
                PurchaseOrder order = purchaseOrderRepository.findById(purchaseOrderId).orElse(null);
                if (order == null || !PENDING_APPROVAL.equals(order.getStatus())) {
                    break;
                }
                Long actorId = approvalWorkflowService.findAnyPendingAssignee(
                        BIZ_PURCHASE_ORDER, String.valueOf(purchaseOrderId));
                if (actorId == null) {
                    actorId = operatorId;
                }
                doReviewPurchaseOrder(actorId, purchaseOrderId, true, "auto flow");
            }
            return null;
        });
    }

    private PurchaseOrderDto doReviewPurchaseOrder(Long operatorId, Long purchaseOrderId,
                                                   boolean approve, String remark) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PURCHASE_ORDER_NOT_FOUND));
        if (!PENDING_APPROVAL.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待审批采购单可审核");
        }
        String bizId = String.valueOf(purchaseOrderId);
        if (!approve) {
            approvalWorkflowService.completeRejected(operatorId, BIZ_PURCHASE_ORDER, bizId, trimToNull(remark));
            order.setStatus("REJECTED");
            purchaseOrderRepository.save(order);
            auditService.record(operatorId, "PURCHASE_ORDER_REJECT", "PURCHASE_ORDER", bizId, trimToNull(remark));
            return toPurchaseDto(order);
        }
        approvalWorkflowService.completeApproved(operatorId, BIZ_PURCHASE_ORDER, bizId, trimToNull(remark));
        if (approvalWorkflowService.isInstanceApproved(BIZ_PURCHASE_ORDER, bizId)) {
            order.setStatus("CREATED");
            auditService.record(operatorId, "PURCHASE_ORDER_APPROVE", "PURCHASE_ORDER", bizId, "审批通过");
        } else {
            auditService.record(operatorId, "PURCHASE_ORDER_APPROVE", "PURCHASE_ORDER", bizId, "审批节点通过");
        }
        return toPurchaseDto(purchaseOrderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public List<PurchaseReturnDto> listPurchaseReturns(Long operatorId) {
        requireWarehouseRead(operatorId);
        return purchaseReturnRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toPurchaseReturnDto)
                .toList();
    }

    @Transactional
    public PurchaseReturnDto createPurchaseReturn(Long operatorId, CreatePurchaseReturnRequest request) {
        requireWarehouseWrite(operatorId);
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "return lines required");
        }
        return runWithPurchaseOrderLock(request.purchaseOrderId(),
                () -> doCreatePurchaseReturn(operatorId, request));
    }

    private PurchaseReturnDto doCreatePurchaseReturn(Long operatorId, CreatePurchaseReturnRequest request) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(request.purchaseOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PURCHASE_ORDER_NOT_FOUND));
        if (!"RECEIVED".equals(order.getStatus()) && !PARTIAL_RECEIVED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "purchase order has no receivable stock to return");
        }
        List<PurchaseOrderLine> existing = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderByLineIdAsc(order.getPurchaseOrderId());

        PurchaseReturn ret = new PurchaseReturn();
        ret.setPurchaseOrderId(order.getPurchaseOrderId());
        ret.setWarehouseId(order.getWarehouseId());
        ret.setSupplierId(order.getSupplierId());
        ret.setStatus("COMPLETED");
        ret.setNotes(trimToNull(request.notes()));
        ret.setOperatorId(operatorId);
        ret.setCreatedAt(Instant.now());
        ret = purchaseReturnRepository.save(ret);
        long returnedValueCents = 0L;

        for (CreatePurchaseReturnRequest.PurchaseReturnLineRequest lineReq : request.lines()) {
            if (lineReq.quantity() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "return quantity must be positive");
            }
            PurchaseOrderLine poLine = existing.stream()
                    .filter(l -> lineReq.purchaseLineId().equals(l.getLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, PURCHASE_LINE_NOT_FOUND));
            int returnable = poLine.getReceivedQty() - poLine.getReturnedQty();
            if (lineReq.quantity() > returnable) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "return qty exceeds returnable for sku=" + poLine.getSkuId()
                                + " returnable=" + returnable);
            }
            warehouseService.returnPurchaseStock(
                    order.getWarehouseId(),
                    poLine.getSkuId(),
                    poLine.getBatchNo(),
                    lineReq.quantity(),
                    operatorId,
                    "PURCHASE_RETURN",
                    String.valueOf(ret.getReturnId())
            );
            returnedValueCents += (long) lineReq.quantity() * poLine.getUnitCostCents();
            poLine.setReturnedQty(poLine.getReturnedQty() + lineReq.quantity());
            purchaseOrderLineRepository.save(poLine);

            PurchaseReturnLine retLine = new PurchaseReturnLine();
            retLine.setReturnId(ret.getReturnId());
            retLine.setPurchaseLineId(poLine.getLineId());
            retLine.setSkuId(poLine.getSkuId());
            retLine.setBatchNo(poLine.getBatchNo());
            retLine.setQuantity(lineReq.quantity());
            purchaseReturnLineRepository.save(retLine);
        }
        supplierPayableService.recordReturn(operatorId, order, returnedValueCents);
        return toPurchaseReturnDto(ret);
    }

    @Transactional
    public PurchaseOrderDto receivePurchaseOrder(Long operatorId, Long purchaseOrderId,
                                                 ReceivePurchaseOrderRequest request) {
        requireWarehouseWrite(operatorId);
        return runWithPurchaseOrderLock(purchaseOrderId,
                () -> doReceivePurchaseOrder(operatorId, purchaseOrderId, request));
    }

    private PurchaseOrderDto doReceivePurchaseOrder(Long operatorId, Long purchaseOrderId,
                                                    ReceivePurchaseOrderRequest request) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PURCHASE_ORDER_NOT_FOUND));
        if (!"CREATED".equals(order.getStatus()) && !PARTIAL_RECEIVED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "purchase order state invalid");
        }
        List<PurchaseOrderLine> existing = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderByLineIdAsc(purchaseOrderId);
        List<PurchaseOrderLineDto> received = request.lines() != null && !request.lines().isEmpty()
                ? request.lines()
                : existing.stream().map(this::toPurchaseLineDto).toList();
        long receivedValueCents = 0L;

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
                    resolveReceiveWarehouse(order, request),
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
            receivedValueCents += (long) deltaQty * line.getUnitCostCents();
        }
        boolean allReceived = purchaseOrderLineRepository.findByPurchaseOrderIdOrderByLineIdAsc(purchaseOrderId)
                .stream()
                .allMatch(line -> line.getReceivedQty() >= line.getOrderedQty());
        order.setStatus(allReceived ? "RECEIVED" : PARTIAL_RECEIVED);
        if (allReceived) {
            order.setReceivedAt(Instant.now());
        }
        if (request.notes() != null && !request.notes().isBlank()) {
            order.setNotes(request.notes().trim());
        }
        supplierPayableService.recordReceive(operatorId, order, receivedValueCents);
        return toPurchaseDto(purchaseOrderRepository.save(order));
    }

    private String resolveReceiveWarehouse(PurchaseOrder order, ReceivePurchaseOrderRequest request) {
        String target = request.receiveWarehouseId() == null || request.receiveWarehouseId().isBlank()
                ? order.getWarehouseId()
                : request.receiveWarehouseId().trim();
        if (target == null || target.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货仓库未指定");
        }
        return target;
    }

    private PurchaseOrderLine matchLine(List<PurchaseOrderLine> existing, PurchaseOrderLineDto dto) {
        if (dto.lineId() != null) {
            return existing.stream()
                    .filter(l -> dto.lineId().equals(l.getLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, PURCHASE_LINE_NOT_FOUND));
        }
        return existing.stream()
                .filter(l -> l.getSkuId().equals(dto.skuId()) && l.getBatchNo().equals(dto.batchNo()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, PURCHASE_LINE_NOT_FOUND));
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
                line.getOrderedQty(), line.getReceivedQty(), line.getUnitCostCents(),
                line.getReturnedQty()
        );
    }

    private PurchaseReturnDto toPurchaseReturnDto(PurchaseReturn ret) {
        return new PurchaseReturnDto(
                ret.getReturnId(),
                ret.getPurchaseOrderId(),
                ret.getWarehouseId(),
                ret.getSupplierId(),
                ret.getStatus(),
                ret.getNotes(),
                ret.getOperatorId(),
                ret.getCreatedAt(),
                purchaseReturnLineRepository.findByReturnIdOrderByLineIdAsc(ret.getReturnId()).stream()
                        .map(l -> new PurchaseReturnLineDto(
                                l.getLineId(), l.getPurchaseLineId(), l.getSkuId(), l.getBatchNo(), l.getQuantity()))
                        .toList()
        );
    }

    private SupplierDto toSupplierDto(Supplier supplier) {
        return new SupplierDto(
                supplier.getSupplierId(), supplier.getSupplierName(), supplier.getContactName(),
                supplier.getContactPhone(), supplier.getStatus(),
                supplier.getPaymentTermsDays(), supplier.getCreditLimitCents(), supplier.getCreatedAt()
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

    private void requireWarehouseRead(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:procurement:list");
    }

    private void requireWarehouseWrite(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:procurement:edit");
    }

    static String purchaseOrderLockKey(Long purchaseOrderId) {
        return "procurement:po:" + purchaseOrderId;
    }

    private <T> T runWithPurchaseOrderLock(Long purchaseOrderId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(purchaseOrderLockKey(purchaseOrderId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "采购单处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(purchaseOrderLockKey(purchaseOrderId));
        }
    }
}
