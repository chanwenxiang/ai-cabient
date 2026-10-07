package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.api.dto.SatelliteSkuOptionDto;
import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ProcurementService {
    private static final Logger log = LoggerFactory.getLogger(ProcurementService.class);

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
    private final WarehouseSupplierIdService warehouseSupplierIdService;
    private final ProcurementService self;

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
                              AdminAuditService auditService,
                              WarehouseSupplierIdService warehouseSupplierIdService,
                              @Lazy ProcurementService self) {
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
        this.warehouseSupplierIdService = warehouseSupplierIdService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> listSuppliers(Long operatorId) {
        requireWarehouseRead(operatorId);
        return self.listSuppliersPage(operatorId, null, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<SupplierDto> listSuppliersPage(
            Long operatorId, String keyword, int page, int size) {
        requireWarehouseRead(operatorId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = supplierRepository.searchPage(keyword, p, s);
        List<SupplierDto> items = result.getRecords().stream().map(this::toSupplierDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public SupplierDto upsertSupplier(Long operatorId, SupplierDto request) {
        requireWarehouseWrite(operatorId);
        String raw = request.supplierId() != null ? request.supplierId().trim() : "";
        boolean createToken = raw.isEmpty() || "new".equalsIgnoreCase(raw) || "_".equals(raw);
        boolean exists = !createToken && supplierRepository.findById(raw).isPresent();
        final String supplierId;
        if (exists) {
            supplierId = raw;
        } else {
            supplierId = warehouseSupplierIdService.resolveSupplierIdForCreate(createToken ? null : raw);
        }
        Supplier supplier = supplierRepository.findById(supplierId).orElseGet(Supplier::new);
        supplier.setSupplierId(supplierId);
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
    public PageResult<PurchaseOrderDto> listPurchaseOrdersPage(
            Long operatorId, String keyword, String warehouseId, boolean returnableOnly,
            boolean excludeTestRef, int page, int size) {
        requireWarehouseRead(operatorId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = purchaseOrderRepository.searchPage(keyword, warehouseId, returnableOnly, excludeTestRef, p, s);
        List<PurchaseOrder> records = result.getRecords();
        List<String> pendingBizIds = records.stream()
                .filter(o -> PENDING_APPROVAL.equals(o.getStatus()))
                .map(o -> String.valueOf(o.getPurchaseOrderId()))
                .toList();
        java.util.Map<String, ApprovalWorkflowService.ApprovalPendingView> pendingViews =
                approvalWorkflowService.pendingViewsForBiz(BIZ_PURCHASE_ORDER, pendingBizIds, operatorId);
        List<PurchaseOrderDto> items = records.stream()
                .map(o -> toPurchaseDto(o, pendingViews.get(String.valueOf(o.getPurchaseOrderId()))))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto getPurchaseOrder(Long operatorId, Long purchaseOrderId) {
        requireWarehouseRead(operatorId);
        PurchaseOrder order = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PURCHASE_ORDER_NOT_FOUND));
        ApprovalWorkflowService.ApprovalPendingView view = null;
        if (PENDING_APPROVAL.equals(order.getStatus())) {
            view = approvalWorkflowService.pendingViewsForBiz(
                    BIZ_PURCHASE_ORDER, List.of(String.valueOf(purchaseOrderId)), operatorId)
                    .get(String.valueOf(purchaseOrderId));
        }
        return toPurchaseDto(order, view);
    }

    @Transactional
    public PurchaseOrderDto createPurchaseOrder(Long operatorId, CreatePurchaseOrderRequest request) {
        requireWarehouseWrite(operatorId);
        String warehouseId = resolveManagedWarehouseId(operatorId, request.warehouseId());
        return persistNewPurchaseOrder(operatorId, request, warehouseId);
    }

    /**
     * 补货员采购入库：不要求 ops:procurement:edit，强制入本人负责的 ACTIVE 分仓。
     */
    @Transactional
    public PurchaseOrderDto createSatellitePurchaseOrder(Long operatorId, CreatePurchaseOrderRequest request) {
        Warehouse mine = requireOperatorManagedWarehouse(operatorId);
        if (request.warehouseId() != null && !request.warehouseId().isBlank()
                && !mine.getWarehouseId().equals(request.warehouseId().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SATELLITE_WAREHOUSE_MISMATCH);
        }
        List<PurchaseOrderLineDto> filled = fillSatelliteLineCosts(request.lines());
        CreatePurchaseOrderRequest body = new CreatePurchaseOrderRequest(
                request.supplierId(), mine.getWarehouseId(), request.refNo(), request.notes(), filled);
        return persistNewPurchaseOrder(operatorId, body, mine.getWarehouseId());
    }

    /**
     * 补货员货到分仓收货：不要求运营采购权，只能收入本人负责的仓。
     */
    @Transactional
    public PurchaseOrderDto receiveSatellitePurchaseOrder(
            Long operatorId, Long purchaseOrderId, ReceivePurchaseOrderRequest request) {
        Warehouse mine = requireOperatorManagedWarehouse(operatorId);
        return runWithPurchaseOrderLock(purchaseOrderId, () -> {
            PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PURCHASE_ORDER_NOT_FOUND));
            if (!mine.getWarehouseId().equals(order.getWarehouseId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SATELLITE_WAREHOUSE_MISMATCH);
            }
            ReceivePurchaseOrderRequest forced = new ReceivePurchaseOrderRequest(
                    request.lines(), request.notes(), mine.getWarehouseId());
            return doReceivePurchaseOrder(operatorId, purchaseOrderId, forced);
        });
    }

    @Transactional(readOnly = true)
    public WarehouseDto getSatelliteWarehouse(Long operatorId) {
        Warehouse w = requireOperatorManagedWarehouse(operatorId);
        return new WarehouseDto(
                w.getWarehouseId(),
                w.getWarehouseName(),
                w.getAddress(),
                w.getStatus(),
                w.getCreatedAt(),
                w.getManagerUserId());
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> listSatelliteSuppliers(Long operatorId) {
        requireOperatorManagedWarehouse(operatorId);
        return supplierRepository.searchPage(null, 0, 100).getRecords().stream()
                .filter(s -> s.getStatus() != null && "ACTIVE".equalsIgnoreCase(s.getStatus()))
                .map(this::toSupplierDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderDto> listSatellitePurchaseOrders(Long operatorId) {
        Warehouse mine = requireOperatorManagedWarehouse(operatorId);
        return purchaseOrderRepository.searchPage(null, mine.getWarehouseId(), false, false, 0, 50)
                .getRecords().stream()
                .map(this::toPurchaseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SatelliteSkuOptionDto> listSatelliteSkus(Long operatorId) {
        requireOperatorManagedWarehouse(operatorId);
        return skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .filter(s -> s.getStatus() != null && "ACTIVE".equalsIgnoreCase(s.getStatus()))
                .limit(80)
                .map(s -> new SatelliteSkuOptionDto(s.getSkuId(), s.getSkuName(), catalogUnitCost(s)))
                .toList();
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
            auditService.appendLog(operatorId, "PURCHASE_ORDER_REJECT", BIZ_PURCHASE_ORDER, bizId, trimToNull(remark));
            return toPurchaseDto(order);
        }
        // P2-4：无启用审批定义时单步直过——否则 isInstanceApproved 恒 false，
        // 采购单会永远卡在 PENDING_APPROVAL（审了但状态不动）。配置了定义则走多节点链不变。
        boolean hasFlow = approvalWorkflowService.isDefinitionEnabled(BIZ_PURCHASE_ORDER);
        approvalWorkflowService.completeApproved(operatorId, BIZ_PURCHASE_ORDER, bizId, trimToNull(remark));
        if (!hasFlow || approvalWorkflowService.isInstanceApproved(BIZ_PURCHASE_ORDER, bizId)) {
            order.setStatus("CREATED");
            auditService.appendLog(operatorId, "PURCHASE_ORDER_APPROVE", BIZ_PURCHASE_ORDER, bizId,
                    hasFlow ? "审批通过" : "审批通过（无审批流配置，单步直过）");
        } else {
            auditService.appendLog(operatorId, "PURCHASE_ORDER_APPROVE", BIZ_PURCHASE_ORDER, bizId, "审批节点通过");
        }
        order = purchaseOrderRepository.save(order);
        ApprovalWorkflowService.ApprovalPendingView view = null;
        if (PENDING_APPROVAL.equals(order.getStatus())) {
            view = approvalWorkflowService.pendingViewsForBiz(BIZ_PURCHASE_ORDER, List.of(bizId), operatorId)
                    .get(bizId);
        }
        return toPurchaseDto(order, view);
    }

    @Transactional(readOnly = true)
    public List<PurchaseReturnDto> listPurchaseReturns(Long operatorId) {
        requireWarehouseRead(operatorId);
        return self.listPurchaseReturnsPage(operatorId, null, null, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseReturnDto> listPurchaseReturnsPage(
            Long operatorId, String keyword, String warehouseId, int page, int size) {
        requireWarehouseRead(operatorId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        var result = purchaseReturnRepository.searchPage(keyword, warehouseId, p, s);
        List<PurchaseReturnDto> items = result.getRecords().stream()
                .map(this::toPurchaseReturnDto)
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
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
        // ---- V318：退货原因分类 / 责任方 / 残次品标记 ----
        // 🔴 不做校验也不给默认值：留 null = 「未分类/未认定」是**可治理的状态**，
        //   填错成 OTHER 则是**假数据**（会让「退得最多的是谁」这个问题失去意义）。
        ret.setReasonCategory(trimToNull(request.reasonCategory()));
        ret.setResponsibleParty(trimToNull(request.responsibleParty()));
        ret.setDefectiveFlag(request.defective());
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前采购单不能收货");
        }
        List<PurchaseOrderLine> existing = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderByLineIdAsc(purchaseOrderId);
        List<PurchaseOrderLineDto> received = request.lines() != null && !request.lines().isEmpty()
                ? request.lines()
                : existing.stream().map(this::toPurchaseLineDto).toList();
        long receivedValueCents = 0L;
        String warehouseId = resolveReceiveWarehouse(order, request);
        for (PurchaseOrderLineDto receiveLine : received) {
            receivedValueCents += processReceiveLine(operatorId, order, existing, receiveLine, warehouseId);
        }
        finalizePurchaseOrderReceive(operatorId, purchaseOrderId, order, request, warehouseId, receivedValueCents);
        return toPurchaseDto(purchaseOrderRepository.save(order));
    }

    private long processReceiveLine(Long operatorId, PurchaseOrder order, List<PurchaseOrderLine> existing,
                                    PurchaseOrderLineDto receiveLine, String warehouseId) {
        PurchaseOrderLine line = matchLine(existing, receiveLine);
        // P1-1：批次/效期收货可覆盖——请求值优先，缺省沿用订单行（下单预估/上次收货值）
        String batchNo = receiveLine.batchNo() != null && !receiveLine.batchNo().isBlank()
                ? receiveLine.batchNo().trim() : line.getBatchNo();
        LocalDate expiryDate = receiveLine.expiryDate() != null
                ? receiveLine.expiryDate() : line.getExpiryDate();
        LocalDate productionDate = receiveLine.productionDate() != null
                ? receiveLine.productionDate() : line.getProductionDate();
        validatePurchaseLine(new PurchaseOrderLineDto(line.getLineId(), line.getSkuId(), batchNo,
                productionDate, expiryDate, 0, 0, line.getUnitCostCents(), 0), true);
        line.setBatchNo(batchNo);
        line.setExpiryDate(expiryDate);
        line.setProductionDate(productionDate);
        int qty = receiveLine.receivedQty() > 0 ? receiveLine.receivedQty() : line.getOrderedQty();
        if (qty <= 0 || qty > line.getOrderedQty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货数量不合法");
        }
        if (qty <= line.getReceivedQty()) {
            return 0L;
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
        warehouseService.receivePurchaseStock(new WarehouseService.PurchaseReceiveCommand(
                warehouseId,
                new WarehouseService.LotSpec(line.getSkuId(), line.getBatchNo(),
                        line.getProductionDate(), line.getExpiryDate()),
                deltaQty,
                line.getUnitCostCents(),
                operatorId,
                BIZ_PURCHASE_ORDER,
                String.valueOf(order.getPurchaseOrderId())));
        return (long) deltaQty * line.getUnitCostCents();
    }

    private void finalizePurchaseOrderReceive(Long operatorId, Long purchaseOrderId, PurchaseOrder order,
                                              ReceivePurchaseOrderRequest request, String receiveWarehouseId,
                                              long receivedValueCents) {
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
        // 实际收货仓回写订单，保证后续退货从同一仓扣减（H33）
        if (!receiveWarehouseId.equals(order.getWarehouseId())) {
            log.warn("purchase order receive warehouse changed orderId={} from={} to={}",
                    purchaseOrderId, order.getWarehouseId(), receiveWarehouseId);
            order.setWarehouseId(receiveWarehouseId);
        }
        supplierPayableService.recordReceive(operatorId, order, receivedValueCents);
    }

    private String resolveReceiveWarehouse(PurchaseOrder order, ReceivePurchaseOrderRequest request) {
        String target = request.receiveWarehouseId() == null || request.receiveWarehouseId().isBlank()
                ? order.getWarehouseId()
                : request.receiveWarehouseId().trim();
        if (target == null || target.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收货仓库未指定");
        }
        // 审计 P2-5：运营侧转投收货仓同样必须「已指定负责人」——货收入无主仓后
        // resolveOutboundWarehouseId 会拒绝从无主仓出库，库存就此搁浅。
        Warehouse targetWarehouse = warehouseRepository.findById(target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库不存在"));
        requireManagedWarehouse(targetWarehouse);
        return target;
    }

    private PurchaseOrderLine matchLine(List<PurchaseOrderLine> existing, PurchaseOrderLineDto dto) {
        if (dto.lineId() != null) {
            return existing.stream()
                    .filter(l -> dto.lineId().equals(l.getLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, PURCHASE_LINE_NOT_FOUND));
        }
        // P1-1：批次可空且收货可覆盖——请求批次先按 sku+batch 精确匹配；
        // 失配（覆盖场景：请求带的是新批次）回退按 sku 匹配，同 sku 多行须传 lineId
        List<PurchaseOrderLine> bySku = existing.stream()
                .filter(l -> l.getSkuId().equals(dto.skuId()))
                .toList();
        if (dto.batchNo() != null && !dto.batchNo().isBlank()) {
            java.util.Optional<PurchaseOrderLine> exact = bySku.stream()
                    .filter(l -> dto.batchNo().equals(l.getBatchNo()))
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        if (bySku.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "同商品存在多行，请按 lineId 指定收货行: " + dto.skuId());
        }
        return bySku.stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, PURCHASE_LINE_NOT_FOUND));
    }

    /**
     * P1-1：批次/效期两段语义——下单（receiving=false）选填（预估批次可填），
     * 收货（receiving=true）必填且可覆盖（真实采购到货验收时才知道批次）。
     */
    private void validatePurchaseLine(PurchaseOrderLineDto dto, boolean receiving) {
        if (dto.skuId() == null || dto.skuId().isBlank()) throw bad("skuId required");
        // SKU 存在性只在下单路径校验：收货行必然引用已存在的订单行（SKU 下单时已验），
        // 收货重验会改变行为（P1-1 引入收货校验时误带出，CI ProcurementReceiveWarehouseTest 抓出）
        if (!receiving && !skuCatalogRepository.existsById(dto.skuId().trim())) {
            throw bad("sku not found: " + dto.skuId());
        }
        if (receiving) {
            if (dto.batchNo() == null || dto.batchNo().isBlank()) throw bad("批次号必填：下单未填时须在收货时录入");
            if (dto.expiryDate() == null) throw bad("到期日期必填：下单未填时须在收货时录入");
        } else if (dto.batchNo() != null && dto.batchNo().isBlank()) {
            throw bad("batchNo cannot be blank (omit instead)");
        }
        if (dto.expiryDate() != null && !dto.expiryDate().isAfter(LocalDate.now())) {
            throw bad("expiryDate must be in future");
        }
        if (dto.productionDate() != null && dto.expiryDate() != null
                && dto.productionDate().isAfter(dto.expiryDate())) {
            throw bad("productionDate cannot be after expiryDate");
        }
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
        return toPurchaseDto(order, null);
    }

    private PurchaseOrderDto toPurchaseDto(PurchaseOrder order, ApprovalWorkflowService.ApprovalPendingView view) {
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
                        .stream().map(this::toPurchaseLineDto).toList(),
                view != null ? view.currentNodeName() : null,
                view != null ? view.pendingForUser() : null
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
                // V318：归因字段必须回传，否则前端看不到「填了没有」，
                // 分类会退化成永远为空的死字段。
                ret.getReasonCategory(),
                ret.getResponsibleParty(),
                ret.getDefectiveFlag(),
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

    private PurchaseOrderDto persistNewPurchaseOrder(
            Long operatorId, CreatePurchaseOrderRequest request, String warehouseId) {
        Supplier supplier = supplierRepository.findById(required(request.supplierId(), "supplierId"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplier not found"));
        if (!"ACTIVE".equalsIgnoreCase(supplier.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "supplier inactive");
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
            order.setRefNo(String.valueOf(order.getPurchaseOrderId()));
            order = purchaseOrderRepository.save(order);
        }

        for (PurchaseOrderLineDto lineDto : request.lines()) {
            validatePurchaseLine(lineDto, false);
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrderId(order.getPurchaseOrderId());
            line.setSkuId(lineDto.skuId().trim());
            line.setBatchNo(trimToNull(lineDto.batchNo()));
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

    private Warehouse requireOperatorManagedWarehouse(Long operatorId) {
        return warehouseRepository.findFirstActiveByManagerUserId(operatorId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, ApiMessages.SATELLITE_WAREHOUSE_REQUIRED));
    }

    private List<PurchaseOrderLineDto> fillSatelliteLineCosts(List<PurchaseOrderLineDto> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().map(line -> {
            if (line.skuId() == null || line.skuId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品未指定，无法下单");
            }
            // 审计 P1-8：分仓采购单价一律以服务端目录价为准。请求里的 unitCostCents 不作信任——
            // 该路径面向无 ops:procurement:edit 权限的补货员，客户端传正单价照单全收会扭曲
            // 供应商应付（receivedValueCents → recordReceive）与成本口径。
            int cost = skuCatalogRepository.findById(line.skuId().trim())
                    .map(ProcurementService::catalogUnitCost)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "商品未维护采购价，无法下单"));
            return new PurchaseOrderLineDto(
                    line.lineId(),
                    line.skuId(),
                    line.batchNo(),
                    line.productionDate(),
                    line.expiryDate(),
                    line.orderedQty(),
                    line.receivedQty(),
                    cost,
                    line.returnedQty());
        }).toList();
    }

    private static int catalogUnitCost(SkuCatalog sku) {
        if (sku.getPurchaseCostCents() != null && sku.getPurchaseCostCents() > 0) {
            return sku.getPurchaseCostCents();
        }
        // 审计批次4（原 P3 升 P2）：售价兜底会把零售价当采购成本，虚增供应商应付与损耗
        // 金额口径。Demo 种子/后台建 SKU 均已要求采购价，缺失即数据不完整——阻断下单
        // 而非兜底（先补目录采购价再采购）。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "商品「" + sku.getSkuName() + "」未维护采购价，请先在商品目录补录后再采购");
    }

    /**
     * 日常采购必须入「已指定负责人」的分仓。
     * 未传仓库时，默认当前操作人作为负责人的仓；没有则拒绝（禁止落到无主中心仓）。
     */
    private String resolveManagedWarehouseId(Long operatorId, String requested) {
        if (requested != null && !requested.isBlank()) {
            Warehouse warehouse = warehouseRepository.findById(requested.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库不存在"));
            requireManagedWarehouse(warehouse);
            return warehouse.getWarehouseId();
        }
        return warehouseRepository.findFirstActiveByManagerUserId(operatorId)
                .map(Warehouse::getWarehouseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "没有已指定负责人的分仓，请先在仓库概览绑定负责人"));
    }

    private static void requireManagedWarehouse(Warehouse warehouse) {
        if (warehouse.getManagerUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该仓库未指定负责人，不能作为日常采购入库仓");
        }
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
