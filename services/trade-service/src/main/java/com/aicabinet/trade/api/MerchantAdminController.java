package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ConfirmLedgerSplitRequest;
import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.MerchantDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ProfitSharingStatusDto;
import com.aicabinet.common.dto.RevenueSplitDto;
import com.aicabinet.common.dto.SubmitProfitSharingRequest;
import com.aicabinet.common.dto.UpsertMerchantRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v2/ops/admin/merchants")
public class MerchantAdminController {

    private final MerchantService merchantService;

    public MerchantAdminController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @RequiresPermissions("ops:merchant:list")
    @GetMapping
    public ApiResponse<PageResult<MerchantDto>> list(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(merchantService.listMerchantsPage(operatorId(request), page, size, q));
    }

    @RequiresPermissions("ops:merchant:edit")
    @PostMapping
    public ApiResponse<MerchantDto> upsert(
            HttpServletRequest request,
            @Valid @RequestBody UpsertMerchantRequest body) {
        return ApiResponse.ok(merchantService.upsertMerchant(operatorId(request), body));
    }

    @RequiresPermissions("ops:merchant:split")
    @GetMapping("/profit-sharing/status")
    public ApiResponse<ProfitSharingStatusDto> profitSharingStatus(HttpServletRequest request) {
        return ApiResponse.ok(merchantService.profitSharingStatus(operatorId(request)));
    }

    @RequiresPermissions("ops:merchant:split")
    @GetMapping("/revenue-splits")
    public ApiResponse<PageResult<RevenueSplitDto>> revenueSplits(
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "status", required = false) String status) {
        return ApiResponse.ok(merchantService.listSplits(operatorId(request), page, size, merchantId, status));
    }

    @RequiresPermissions("ops:merchant:export")
    @GetMapping(value = "/revenue-splits/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportRevenueSplits(
            HttpServletRequest request,
            @RequestParam(name = "merchantId", required = false) String merchantId,
            @RequestParam(name = "status", required = false) String status) {
        byte[] csv = merchantService.exportSplitsCsv(operatorId(request), merchantId, status);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"revenue-splits.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    @RequiresPermissions("ops:merchant:split")
    @PostMapping("/revenue-splits/{splitId}/wechat-submit")
    public ApiResponse<RevenueSplitDto> submitWeChatProfitSharing(
            HttpServletRequest request,
            @PathVariable String splitId,
            @RequestBody(required = false) SubmitProfitSharingRequest body) {
        return ApiResponse.ok(merchantService.submitWeChatProfitSharing(operatorId(request), splitId, body));
    }

    @RequiresPermissions("ops:merchant:split")
    @PostMapping("/revenue-splits/{splitId}/wechat-refresh")
    public ApiResponse<RevenueSplitDto> refreshWeChatProfitSharing(
            HttpServletRequest request,
            @PathVariable String splitId) {
        return ApiResponse.ok(merchantService.refreshWeChatProfitSharing(operatorId(request), splitId));
    }

    @RequiresPermissions("ops:merchant:split")
    @PostMapping("/revenue-splits/{splitId}/confirm-ledger")
    public ApiResponse<RevenueSplitDto> confirmLedgerOnly(
            HttpServletRequest request,
            @PathVariable String splitId,
            @Valid @RequestBody ConfirmLedgerSplitRequest body) {
        return ApiResponse.ok(merchantService.confirmLedgerOnly(operatorId(request), splitId, body.reason()));
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
