package com.aicabinet.trade.api;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.ProcurementService;
import com.aicabinet.trade.service.PurchaseSuggestionService;
import com.aicabinet.trade.service.SupplierPayableService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 采购 / 供应商 / 应付（从 {@link OpsCommercialController} 按域抽出；URL 不变）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin")
public class OpsProcurementController {

    private final ProcurementService procurementService;
    private final PurchaseSuggestionService purchaseSuggestionService;
    private final SupplierPayableService supplierPayableService;

    public OpsProcurementController(ProcurementService procurementService,
                                    PurchaseSuggestionService purchaseSuggestionService,
                                    SupplierPayableService supplierPayableService) {
        this.procurementService = procurementService;
        this.purchaseSuggestionService = purchaseSuggestionService;
        this.supplierPayableService = supplierPayableService;
    }

    // --- 采购 / 供应商 / 应付 ---
    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/suppliers")
    public ApiResponse<PageResult<SupplierDto>> suppliers(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(procurementService.listSuppliersPage(operatorId(request), q, page, size));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PutMapping("/suppliers/{supplierId}")
    public ApiResponse<SupplierDto> upsertSupplier(
            HttpServletRequest request,
            @PathVariable String supplierId,
            @RequestBody SupplierDto body) {
        SupplierDto merged = new SupplierDto(
                supplierId,
                body.supplierName(),
                body.contactName(),
                body.contactPhone(),
                body.status(),
                body.paymentTermsDays(),
                body.creditLimitCents(),
                body.createdAt()
        );
        return ApiResponse.ok(procurementService.upsertSupplier(operatorId(request), merged));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/purchase-orders")
    public ApiResponse<PageResult<PurchaseOrderDto>> purchaseOrders(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "warehouseId", required = false) String warehouseId,
            @RequestParam(name = "returnableOnly", defaultValue = "false") boolean returnableOnly,
            @RequestParam(name = "excludeTestRef", defaultValue = "false") boolean excludeTestRef,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(procurementService.listPurchaseOrdersPage(
                operatorId(request), q, warehouseId, returnableOnly, excludeTestRef, page, size));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/purchase-orders/{purchaseOrderId}")
    public ApiResponse<PurchaseOrderDto> purchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId) {
        return ApiResponse.ok(procurementService.getPurchaseOrder(operatorId(request), purchaseOrderId));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/procurement/suggestions")
    public ApiResponse<PageResult<PurchaseSuggestionDto>> purchaseSuggestions(
            HttpServletRequest request,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(required = false) Integer leadTimeDays,
            @RequestParam(required = false) Integer coverageDays,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(purchaseSuggestionService.suggestPage(
                operatorId(request),
                warehouseId,
                leadTimeDays == null ? 0 : leadTimeDays,
                coverageDays == null ? 0 : coverageDays,
                page,
                size));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/purchase-orders")
    public ApiResponse<PurchaseOrderDto> createPurchaseOrder(
            HttpServletRequest request,
            @Valid @RequestBody CreatePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.createPurchaseOrder(operatorId(request), body));
    }

    @RequiresPermissions(value = {"ops:procurement:edit", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/purchase-orders/{purchaseOrderId}/review")
    public ApiResponse<PurchaseOrderDto> reviewPurchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId,
            @RequestBody java.util.Map<String, Object> body) {
        boolean approve = body != null && Boolean.TRUE.equals(body.get("approve"));
        String remark = body != null && body.get("remark") != null ? String.valueOf(body.get("remark")) : null;
        return ApiResponse.ok(procurementService.reviewPurchaseOrder(
                operatorId(request), purchaseOrderId, approve, remark));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/purchase-orders/{purchaseOrderId}/receive")
    public ApiResponse<PurchaseOrderDto> receivePurchaseOrder(
            HttpServletRequest request,
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody ReceivePurchaseOrderRequest body) {
        return ApiResponse.ok(procurementService.receivePurchaseOrder(operatorId(request), purchaseOrderId, body));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/purchase-returns")
    public ApiResponse<PageResult<PurchaseReturnDto>> purchaseReturns(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "warehouseId", required = false) String warehouseId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(procurementService.listPurchaseReturnsPage(
                operatorId(request), q, warehouseId, page, size));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/purchase-returns")
    public ApiResponse<PurchaseReturnDto> createPurchaseReturn(
            HttpServletRequest request,
            @Valid @RequestBody CreatePurchaseReturnRequest body) {
        return ApiResponse.ok(procurementService.createPurchaseReturn(operatorId(request), body));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/suppliers/payables")
    public ApiResponse<PageResult<SupplierPayableDto>> payables(
            HttpServletRequest request,
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(supplierPayableService.listPayablesPage(
                operatorId(request), supplierId, status, overdueOnly, page, size));
    }

    @RequiresPermissions("ops:procurement:list")
    @GetMapping("/suppliers/payables/summary")
    public ApiResponse<List<SupplierPayableSummaryDto>> payableSummary(
            HttpServletRequest request,
            @RequestParam(required = false) String supplierId) {
        return ApiResponse.ok(supplierPayableService.summary(operatorId(request), supplierId));
    }

    @RequiresPermissions("ops:procurement:edit")
    @PostMapping("/suppliers/payables/{payableId}/pay")
    public ApiResponse<SupplierPayableDto> payPayable(
            HttpServletRequest request,
            @PathVariable Long payableId,
            @Valid @RequestBody PaySupplierRequest body) {
        return ApiResponse.ok(supplierPayableService.pay(operatorId(request), payableId, body));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
