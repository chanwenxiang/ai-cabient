package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantWalletAccountDto;
import com.aicabinet.common.dto.MerchantWalletLedgerDto;
import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantWithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/merchant-wallets")
public class MerchantWalletController {
    private static final String AMOUNTCENTS = "amountCents";


    private final MerchantWithdrawService merchantWithdrawService;

    public MerchantWalletController(MerchantWithdrawService merchantWithdrawService) {
        this.merchantWithdrawService = merchantWithdrawService;
    }

    @RequiresPermissions(value = {"ops:merchant-withdraw:list", "ops:finance:view", "ops:merchant:list"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<MerchantWalletAccountDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(merchantWithdrawService.listAccounts(operator(request), keyword, page, size));
    }

    @RequiresPermissions(value = {"ops:merchant-withdraw:list", "ops:finance:view", "ops:merchant:list"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping("/{merchantId}/ledgers")
    public ApiResponse<List<MerchantWalletLedgerDto>> ledgers(
            HttpServletRequest request,
            @PathVariable String merchantId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(merchantWithdrawService.ledgers(operator(request), merchantId, limit));
    }

    @RequiresPermissions("ops:merchant-withdraw:adjust")
    @PostMapping("/{merchantId}/adjust")
    public ApiResponse<MerchantWalletAccountDto> adjust(
            HttpServletRequest request,
            @PathVariable String merchantId,
            @RequestBody Map<String, Object> body) {
        long amount = body.get(AMOUNTCENTS) instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get(AMOUNTCENTS)));
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return ApiResponse.ok(merchantWithdrawService.adjust(operator(request), merchantId, amount, remark));
    }

    @RequiresPermissions("ops:merchant-withdraw:adjust")
    @PostMapping("/{merchantId}/withdraw")
    public ApiResponse<MerchantWithdrawRequestDto> withdraw(
            HttpServletRequest request,
            @PathVariable String merchantId,
            @RequestBody Map<String, Object> body) {
        long amount = body.get(AMOUNTCENTS) instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get(AMOUNTCENTS)));
        String requestNo = body.get("requestNo") == null ? null : String.valueOf(body.get("requestNo"));
        return ApiResponse.ok(merchantWithdrawService.apply(merchantId, amount, requestNo));
    }

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
