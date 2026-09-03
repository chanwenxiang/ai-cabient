package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.LineWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.LineWithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/line-withdraws")
public class LineWithdrawController {

    private final LineWithdrawService lineWithdrawService;

    public LineWithdrawController(LineWithdrawService lineWithdrawService) {
        this.lineWithdrawService = lineWithdrawService;
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:line-withdraw:review", "ops:finance:view"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping("/payout-mode")
    public ApiResponse<Map<String, Object>> payoutMode(HttpServletRequest request) {
        return ApiResponse.ok(lineWithdrawService.payoutMode(operator(request)));
    }

    @RequiresPermissions(value = {"ops:line-manager:list", "ops:line-withdraw:review", "ops:finance:view"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<LineWithdrawRequestDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long managerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(lineWithdrawService.list(operator(request), status, managerId, page, size));
    }

    @RequiresPermissions("ops:line-withdraw:review")
    @PostMapping("/{requestId}/review")
    public ApiResponse<LineWithdrawRequestDto> review(
            HttpServletRequest request, @PathVariable long requestId, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("approve")));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return ApiResponse.ok(lineWithdrawService.review(operator(request), requestId, approve, remark));
    }

    @RequiresPermissions("ops:line-withdraw:review")
    @PostMapping("/{requestId}/payout")
    public ApiResponse<LineWithdrawRequestDto> payout(HttpServletRequest request, @PathVariable long requestId) {
        return ApiResponse.ok(lineWithdrawService.payout(operator(request), requestId));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
