package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.domain.StaffWalletAccount;
import com.aicabinet.trade.domain.StaffWalletLedger;
import com.aicabinet.trade.mapper.StaffWalletAccountMapper;
import com.aicabinet.trade.mapper.StaffWalletLedgerMapper;
import com.aicabinet.trade.service.StaffWalletService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 补货员钱包（V359，对齐老系统 CommissionAccount）：佣金入钱包（资金视角），
 * 提现=线下打款后运营标记出账（余额不足即失败——「没存钱就会提现失败」）。
 * 权限沿用线长钱包域口径 ops:line-manager:list / ops:line-manager:edit。
 */
@Tag(name = "Ops 补货员钱包")
@RestController
@RequestMapping("/api/v2/ops/admin/staff-wallets")
public class OpsStaffWalletController {

    private final StaffWalletAccountMapper accountMapper;
    private final StaffWalletLedgerMapper ledgerMapper;
    private final StaffWalletService staffWalletService;

    public OpsStaffWalletController(StaffWalletAccountMapper accountMapper,
                                    StaffWalletLedgerMapper ledgerMapper,
                                    StaffWalletService staffWalletService) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.staffWalletService = staffWalletService;
    }

    @Operation(summary = "补货员钱包余额列表")
    @GetMapping
    @RequiresPermissions("ops:line-manager:list")
    public ApiResponse<List<StaffWalletAccount>> list() {
        return ApiResponse.ok(accountMapper.selectList(
                Wrappers.<StaffWalletAccount>lambdaQuery().orderByDesc(StaffWalletAccount::getBalanceCents)));
    }

    @Operation(summary = "某补货员钱包流水")
    @GetMapping("/{staffUserId}/ledger")
    @RequiresPermissions("ops:line-manager:list")
    public ApiResponse<List<StaffWalletLedger>> ledger(@PathVariable long staffUserId) {
        return ApiResponse.ok(ledgerMapper.selectList(Wrappers.<StaffWalletLedger>lambdaQuery()
                .eq(StaffWalletLedger::getStaffUserId, staffUserId)
                .orderByDesc(StaffWalletLedger::getLedgerId)));
    }

    @Operation(summary = "标记线下打款出账（余额不足即失败；每次标记=一笔独立出账流水）")
    @PostMapping("/{staffUserId}/payout")
    @RequiresPermissions("ops:line-manager:edit")
    public ApiResponse<Boolean> payout(@PathVariable long staffUserId,
                                       @RequestBody Map<String, Object> body) {
        long amountCents = Long.parseLong(String.valueOf(body.get("amountCents")).trim());
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        // 🔴 幂等键（审计 🔴5）：前端每次打开打款弹窗生成一个 requestNo，重复提交/网络重试
        // 只扣一次；未传时退化为请求内唯一键（兼容旧调用方，但不再承诺跨请求幂等）。
        String requestNo = body.get("requestNo") == null || String.valueOf(body.get("requestNo")).isBlank()
                ? "STAFF_PAYOUT:" + staffUserId + ":" + System.nanoTime()
                : String.valueOf(body.get("requestNo")).trim();
        boolean applied = staffWalletService.payoutIfAbsent(staffUserId, amountCents,
                "WITHDRAW_PAYOUT", "STAFF_WITHDRAW", requestNo,
                remark == null ? "线下打款出账" : remark);
        return ApiResponse.ok(applied);
    }
}
