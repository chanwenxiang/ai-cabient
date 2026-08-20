package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.BalanceRefundRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReviewBalanceRefundRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.BalanceRefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/ops/admin/balance-refunds")
public class AdminBalanceRefundController {

    private final BalanceRefundService balanceRefundService;

    public AdminBalanceRefundController(BalanceRefundService balanceRefundService) {
        this.balanceRefundService = balanceRefundService;
    }

    @RequiresPermissions(value = {
            "ops:balance-refund:list", "ops:balance-refund:review", "ops:finance:view"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<BalanceRefundRequestDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(balanceRefundService.listAdmin(operatorId, status, userId, page, size));
    }

    @RequiresPermissions("ops:balance-refund:review")
    @PostMapping("/{requestId}/review")
    public ApiResponse<BalanceRefundRequestDto> review(
            HttpServletRequest request,
            @PathVariable long requestId,
            @Valid @RequestBody ReviewBalanceRefundRequest body) {
        Long operatorId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        boolean approve = Boolean.TRUE.equals(body.approve());
        return ApiResponse.ok(balanceRefundService.review(operatorId, requestId, approve, body.remark()));
    }
}
