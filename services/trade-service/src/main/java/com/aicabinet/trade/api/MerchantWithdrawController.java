package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantWithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/merchant-withdraws")
public class MerchantWithdrawController {

    private final MerchantWithdrawService merchantWithdrawService;

    public MerchantWithdrawController(MerchantWithdrawService merchantWithdrawService) {
        this.merchantWithdrawService = merchantWithdrawService;
    }

    @RequiresPermissions(value = {
            "ops:merchant-withdraw:list", "ops:merchant-withdraw:review", "ops:finance:view"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping("/payout-mode")
    public ApiResponse<Map<String, Object>> payoutMode(HttpServletRequest request) {
        return ApiResponse.ok(merchantWithdrawService.payoutMode(operator(request)));
    }

    @RequiresPermissions(value = {
            "ops:merchant-withdraw:list", "ops:merchant-withdraw:review", "ops:finance:view"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<MerchantWithdrawRequestDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(merchantWithdrawService.listWithdraws(
                operator(request), status, merchantId, page, size));
    }

    @RequiresPermissions("ops:merchant-withdraw:review")
    @PostMapping("/{requestId}/review")
    public ApiResponse<MerchantWithdrawRequestDto> review(
            HttpServletRequest request, @PathVariable long requestId, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approve")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return ApiResponse.ok(merchantWithdrawService.review(operator(request), requestId, approve, remark));
    }

    @RequiresPermissions("ops:merchant-withdraw:review")
    @PostMapping("/{requestId}/payout")
    public ApiResponse<MerchantWithdrawRequestDto> payout(
            HttpServletRequest request, @PathVariable long requestId) {
        return ApiResponse.ok(merchantWithdrawService.payout(operator(request), requestId));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
