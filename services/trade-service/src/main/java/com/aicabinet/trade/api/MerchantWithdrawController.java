package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantWithdrawService;
import com.aicabinet.trade.service.PayoutReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/merchant-withdraws")
public class MerchantWithdrawController {

    private final MerchantWithdrawService merchantWithdrawService;
    private final PayoutReconciliationService payoutReconciliationService;

    public MerchantWithdrawController(MerchantWithdrawService merchantWithdrawService,
                                      PayoutReconciliationService payoutReconciliationService) {
        this.merchantWithdrawService = merchantWithdrawService;
        this.payoutReconciliationService = payoutReconciliationService;
    }

    @RequiresPermissions(value = {
            "ops:merchant-withdraw:list", "ops:merchant-withdraw:review", "ops:finance:view"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping("/payout-mode")
    public ApiResponse<Map<String, Object>> payoutMode(HttpServletRequest request) {
        return ApiResponse.ok(merchantWithdrawService.payoutMode(operator(request)));
    }

    /**
     * V308：出款侧对账（本地自洽口径）。
     *
     * <p>🔴 <b>权限只给财务只读</b>，不给 {@code review}：对账报告能看出各通道出款总额，
     * 属于资金敏感信息；能打款的人不需要靠它工作。
     *
     * <p>⚠️ 返回里的 {@code scope} 恒为 {@code LOCAL_SELF_CONSISTENT_ONLY} ——
     * 真实通道未接通，<b>没有渠道账单可比</b>。运营不可把它当「与微信对平」的依据。
     */
    @RequiresPermissions("ops:finance:view")
    @GetMapping("/payout-reconciliation")
    public ApiResponse<Map<String, Object>> payoutReconciliation(
            HttpServletRequest request,
            @RequestParam(required = false) String date) {
        LocalDate bizDate = null;
        if (date != null && !date.isBlank()) {
            try {
                bizDate = LocalDate.parse(date.trim());
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式应为 yyyy-MM-dd");
            }
        }
        PayoutReconciliationService.PayoutReconReport report =
                payoutReconciliationService.reconcile(bizDate);
        return ApiResponse.ok(report.detail());
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

    @RequiresPermissions("ops:merchant-withdraw:review")
    @PostMapping("/{requestId}/cancel")
    public ApiResponse<MerchantWithdrawRequestDto> cancel(
            HttpServletRequest request, @PathVariable long requestId,
            @RequestBody(required = false) Map<String, Object> body) {
        String remark = body == null || body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return ApiResponse.ok(merchantWithdrawService.cancelFailed(operator(request), requestId, remark));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
