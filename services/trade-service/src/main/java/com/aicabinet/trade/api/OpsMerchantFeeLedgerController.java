package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.aicabinet.trade.mapper.MerchantFeeLedgerMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 商户费用台账（V355）：列表 / 标记结清。
 * 审计 🔴1 修复：此前台账只写不查、无结清入口（PENDING 永久堆积、运营无法对账）。
 * 权限沿用商户资金域口径 ops:merchant-withdraw（list 查看 / edit 处置）。
 */
@Tag(name = "Ops 商户费用台账")
@RestController
@RequestMapping("/api/v2/ops/admin/merchant-fee-ledgers")
public class OpsMerchantFeeLedgerController {

    private final MerchantFeeLedgerMapper feeLedgerMapper;

    public OpsMerchantFeeLedgerController(MerchantFeeLedgerMapper feeLedgerMapper) {
        this.feeLedgerMapper = feeLedgerMapper;
    }

    @Operation(summary = "台账列表（按商户/状态/费用类型过滤）")
    @GetMapping
    @RequiresPermissions("ops:merchant-withdraw:list")
    public ApiResponse<List<MerchantFeeLedger>> list(@RequestParam(required = false) String merchantId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String feeType) {
        return ApiResponse.ok(feeLedgerMapper.selectList(Wrappers.<MerchantFeeLedger>lambdaQuery()
                .eq(merchantId != null && !merchantId.isBlank(), MerchantFeeLedger::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), MerchantFeeLedger::getStatus, status)
                .eq(feeType != null && !feeType.isBlank(), MerchantFeeLedger::getFeeType, feeType)
                .orderByDesc(MerchantFeeLedger::getLedgerId)));
    }

    @Operation(summary = "标记结清（线下收款后；幂等：已结清直接返回）")
    @PostMapping("/{ledgerId}/settle")
    @RequiresPermissions("ops:merchant-withdraw:edit")
    public ApiResponse<MerchantFeeLedger> settle(@PathVariable Long ledgerId,
                                                 @RequestParam(required = false) String remark) {
        MerchantFeeLedger row = feeLedgerMapper.selectById(ledgerId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "台账记录不存在");
        }
        if ("SETTLED".equals(row.getStatus())) {
            return ApiResponse.ok(row);
        }
        row.setStatus("SETTLED");
        row.setSettledAt(Instant.now());
        if (remark != null && !remark.isBlank()) {
            row.setRemark(remark.trim());
        }
        feeLedgerMapper.updateById(row);
        return ApiResponse.ok(row);
    }
}
