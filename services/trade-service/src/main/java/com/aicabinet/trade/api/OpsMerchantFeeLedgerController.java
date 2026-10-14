package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.aicabinet.trade.service.MerchantFeeLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商户费用台账（V355）：列表 / 标记结清。
 * 审计 🔴1 修复：此前台账只写不查、无结清入口（PENDING 永久堆积、运营无法对账）。
 * 权限沿用商户资金域口径 ops:merchant-withdraw（list 查看 / adjust 资金处置——
 * 结清=资金台账处置，2026-10-14 由 edit 改 adjust：edit 未种入 ops_permission，adjust 已种子）。
 */
@Tag(name = "Ops 商户费用台账")
@RestController
@RequestMapping("/api/v2/ops/admin/merchant-fee-ledgers")
public class OpsMerchantFeeLedgerController {

    private final MerchantFeeLedgerService feeLedgerService;

    public OpsMerchantFeeLedgerController(MerchantFeeLedgerService feeLedgerService) {
        this.feeLedgerService = feeLedgerService;
    }

    @Operation(summary = "台账列表（按商户/状态/费用类型过滤）")
    @GetMapping
    @RequiresPermissions("ops:merchant-withdraw:list")
    public ApiResponse<List<MerchantFeeLedger>> list(@RequestParam(required = false) String merchantId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String feeType) {
        return ApiResponse.ok(feeLedgerService.list(merchantId, status, feeType));
    }

    @Operation(summary = "标记结清（线下收款后；幂等：已结清直接返回）")
    @PostMapping("/{ledgerId}/settle")
    @RequiresPermissions("ops:merchant-withdraw:adjust")
    public ApiResponse<MerchantFeeLedger> settle(@PathVariable Long ledgerId,
                                                 @RequestParam(required = false) String remark) {
        return ApiResponse.ok(feeLedgerService.settle(ledgerId, remark));
    }
}
