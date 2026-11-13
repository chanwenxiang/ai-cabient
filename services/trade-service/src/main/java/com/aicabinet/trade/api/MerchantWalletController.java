package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantWalletAccountDto;
import com.aicabinet.common.dto.MerchantWalletLedgerDto;
import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.WalletAdjustRequest;
import com.aicabinet.common.dto.WithdrawApplyRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantArrearsWriteOffService;
import com.aicabinet.trade.service.MerchantWithdrawService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/ops/admin/merchant-wallets")
public class MerchantWalletController {

    private final MerchantWithdrawService merchantWithdrawService;
    private final MerchantArrearsWriteOffService arrearsWriteOffService;

    public MerchantWalletController(MerchantWithdrawService merchantWithdrawService,
                                    MerchantArrearsWriteOffService arrearsWriteOffService) {
        this.merchantWithdrawService = merchantWithdrawService;
        this.arrearsWriteOffService = arrearsWriteOffService;
    }

    @RequiresPermissions(value = {"ops:merchant-withdraw:list", "ops:finance:view", "ops:merchant:list"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping
    public ApiResponse<PageResult<MerchantWalletAccountDto>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean onlyNegative) {
        return ApiResponse.ok(merchantWithdrawService.listAccounts(
                operator(request), keyword, page, size, onlyNegative));
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
            @Valid @RequestBody WalletAdjustRequest body) {
        return ApiResponse.ok(merchantWithdrawService.adjust(
                operator(request), merchantId, body.amountCents(), body.remark()));
    }

    @RequiresPermissions("ops:merchant-withdraw:adjust")
    @PostMapping("/{merchantId}/withdraw")
    public ApiResponse<MerchantWithdrawRequestDto> withdraw(
            HttpServletRequest request,
            @PathVariable String merchantId,
            @Valid @RequestBody WithdrawApplyRequest body) {
        return ApiResponse.ok(merchantWithdrawService.apply(
                operator(request), merchantId, body.amountCents(), body.requestNo()));
    }

    /**
     * CB-086：欠款名单 CSV（线下追缴用）。管理端 token 在 sessionStorage，前端以 blob 下载。
     */
    @RequiresPermissions(value = {"ops:merchant-withdraw:list", "ops:finance:view", "ops:merchant:list"},
            logical = RequiresPermissions.Logical.OR)
    @GetMapping("/arrears.csv")
    public ResponseEntity<byte[]> arrearsCsv(HttpServletRequest request) {
        byte[] body = merchantWithdrawService.arrearsCsv(operator(request))
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"merchant-wallet-arrears.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /**
     * CB-086 第 4.1 条：欠款核销（平台兜底）。门槛见 {@link MerchantArrearsWriteOffService}：
     * 非 ACTIVE、无在途提现/分账、金额取执行时实际负余额、幂等、审计 + 大额同批发起审批。
     */
    @RequiresPermissions("ops:merchant-withdraw:adjust")
    @PostMapping("/{merchantId}/arrears-writeoff")
    public ApiResponse<MerchantArrearsWriteOffService.WriteOffResult> writeOffArrears(
            HttpServletRequest request,
            @PathVariable String merchantId,
            @RequestBody(required = false) ArrearsWriteOffRequest body) {
        return ApiResponse.ok(arrearsWriteOffService.writeOff(
                operator(request), merchantId, body == null ? null : body.reason()));
    }

    /** 核销请求体（reason 可空，落审计时兜底为「平台兜底」）。 */
    public record ArrearsWriteOffRequest(String reason) {}

    private Long operator(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
