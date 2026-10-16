package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.aicabinet.trade.service.MerchantDailyIncomeNotifyScheduler;
import com.aicabinet.trade.service.MerchantFeeLedgerService;
import com.aicabinet.trade.support.ScheduleZones;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 商户费用台账（V355）：列表 / 标记结清 / 按日期重跑日扣。
 * 审计 🔴1 修复：此前台账只写不查、无结清入口（PENDING 永久堆积、运营无法对账）。
 * 权限沿用商户资金域口径 ops:merchant-withdraw（list 查看 / adjust 资金处置——
 * 结清=资金台账处置，2026-10-14 由 edit 改 adjust：edit 未种入 ops_permission，adjust 已种子）。
 * 2026-10-16 F-2/F-4 拍板：扣款成功即写 SETTLED 行，漏扣**不自动回补**，由此处的
 * 「按日期重跑日扣」人工补（幂等只补「那天没扣成」的商户，不重扣、不追平当天改过的费率）。
 */
@Tag(name = "Ops 商户费用台账")
@RestController
@RequestMapping("/api/v2/ops/admin/merchant-fee-ledgers")
public class OpsMerchantFeeLedgerController {

    /** 重跑回溯上限（天）：补扣是「补昨天/上周漏的那一天」，不是随便往历史里扣钱。 */
    static final int RERUN_LOOKBACK_DAYS = 92;

    private final MerchantFeeLedgerService feeLedgerService;
    private final MerchantDailyIncomeNotifyScheduler feeScheduler;

    public OpsMerchantFeeLedgerController(MerchantFeeLedgerService feeLedgerService,
                                          MerchantDailyIncomeNotifyScheduler feeScheduler) {
        this.feeLedgerService = feeLedgerService;
        this.feeScheduler = feeScheduler;
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

    @Operation(summary = "按日期重跑周期费用日扣（F-4 拍板：漏扣不自动补，人工带日期补跑）")
    @PostMapping("/rerun-daily-fees")
    @RequiresPermissions("ops:merchant-withdraw:adjust")
    public ApiResponse<Map<String, Object>> rerunDailyFees(@RequestParam String feeDate) {
        LocalDate date = parseFeeDate(feeDate);
        LocalDate today = LocalDate.now(ScheduleZones.ZONE);
        if (date.isAfter(today.minusDays(1))) {
            // 只允许重跑「已过去的一天」：跑今天/未来等于提前扣商户的钱
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "feeDate 只能是不晚于昨天的日期（避免提前扣费），当前允许到 " + today.minusDays(1));
        }
        if (date.isBefore(today.minusDays(RERUN_LOOKBACK_DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "feeDate 回溯不得超过 " + RERUN_LOOKBACK_DAYS + " 天（久历史请走线下对账）");
        }
        long charged = feeScheduler.rerunDailyFees(date);
        return ApiResponse.ok(Map.of(
                "feeDate", date.toString(),
                "chargedCents", charged,
                "note", "只补「该日没扣成」的商户：已扣过的不重扣，当天改过费率的也不会追平差额"));
    }

    private static LocalDate parseFeeDate(String feeDate) {
        try {
            return LocalDate.parse(feeDate.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feeDate 须为 YYYY-MM-DD");
        }
    }
}
