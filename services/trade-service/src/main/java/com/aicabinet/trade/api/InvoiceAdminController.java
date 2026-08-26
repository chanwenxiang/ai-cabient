package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.InvoiceRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.InvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/invoices")
public class InvoiceAdminController {

    private final InvoiceService invoiceService;

    public InvoiceAdminController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @RequiresPermissions(value = {"ops:invoice:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<InvoiceRequestDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long op = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(invoiceService.listForOpsPage(op, status, page, size));
    }

    @RequiresPermissions("ops:invoice:edit")
    @PostMapping("/{invoiceId}/issue")
    public ApiResponse<InvoiceRequestDto> issue(
            HttpServletRequest request,
            @PathVariable Long invoiceId) {
        Long op = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(invoiceService.issue(op, invoiceId));
    }

    @RequiresPermissions("ops:invoice:edit")
    @PostMapping("/{invoiceId}/reject")
    public ApiResponse<InvoiceRequestDto> reject(
            HttpServletRequest request,
            @PathVariable Long invoiceId,
            @RequestBody(required = false) Map<String, String> body) {
        Long op = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(invoiceService.reject(op, invoiceId, reason));
    }

    @RequiresPermissions(value = {"ops:invoice:list", "ops:finance:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/tax-profile")
    public ApiResponse<com.aicabinet.common.dto.MerchantTaxProfileDto> taxForOrder(
            HttpServletRequest request,
            @RequestParam String orderId) {
        Long op = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(invoiceService.taxProfileForOrder(op, orderId));
    }
}
