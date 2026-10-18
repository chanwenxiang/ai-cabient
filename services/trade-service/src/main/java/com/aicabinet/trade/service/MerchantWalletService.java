package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWalletLedger;
import com.aicabinet.trade.domain.WalletAccount;
import com.aicabinet.trade.domain.WalletLedger;
import com.aicabinet.trade.service.wallet.WalletDomain;
import com.aicabinet.trade.service.wallet.WalletEngine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 商户钱包 facade（V139）。V388 三域物理统一后仅保留 facade 契约（{@code String merchantId}
 * 入参、{@link MerchantWalletAccount} 返回类型、{@link ReverseCreditCommand} 记录全不变，
 * 控制器/提现服务/openapi 无感），资金写路径委托 {@link WalletEngine}（domain=MERCHANT）。
 *
 * <p>商户特有能力：可用余额扣款、完整提现冻结生命周期、{@code reverseCreditIfPresent} 冲正、
 * {@code debitedCentsByRef} 展示口径；{@code owner_snapshot_name}（旧 merchant_name 冗余）由
 * 引擎按域解析。商户 ID 空值校验留在 facade（引擎域无关，不做 merchant 专属校验）。
 */
@Service
public class MerchantWalletService {

    private static final WalletDomain DOMAIN = WalletDomain.MERCHANT;

    private final WalletEngine engine;

    public MerchantWalletService(WalletEngine engine) {
        this.engine = engine;
    }

    public record ReverseCreditCommand(
            String reverseRefType, String reverseRefId, String originalRefType, String originalRefId, String remark) {}

    @Transactional
    public MerchantWalletAccount ensureAccount(String merchantId) {
        requireMerchantId(merchantId);
        return toAccount(engine.ensureAccount(DOMAIN, merchantId));
    }

    /** 只读查询：不自动建户（列表/详情视图，无副作用）。 */
    @Transactional(readOnly = true)
    public MerchantWalletAccount findAccount(String merchantId) {
        if (merchantId == null) {
            return null;
        }
        return toAccount(engine.findAccount(DOMAIN, merchantId));
    }

    /** 某商户最近 N 条流水（created_at 倒序），映射回 {@link MerchantWalletLedger} 供提现服务视图复用。 */
    @Transactional(readOnly = true)
    public List<MerchantWalletLedger> listRecentLedger(String merchantId, int limit) {
        if (merchantId == null) {
            return List.of();
        }
        return engine.listLedgerByCreatedAtDesc(DOMAIN, merchantId, limit).stream()
                .map(MerchantWalletService::toLedger)
                .toList();
    }

    @Transactional
    public boolean creditIfAbsent(String merchantId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        return engine.creditIfAbsent(DOMAIN, merchantId, amountCents, entryType, refType, refId, remark);
    }

    @Transactional
    public boolean reverseCreditIfPresent(String merchantId, long amountCents, String entryType,
                                          ReverseCreditCommand command) {
        requireMerchantId(merchantId);
        return engine.reverseCreditIfPresent(DOMAIN, merchantId, amountCents, entryType,
                command.reverseRefType(), command.reverseRefId(),
                command.originalRefType(), command.originalRefId(), command.remark());
    }

    /**
     * 幂等扣款且允许扣成负数（押金收取：直减可负——余额不足也应收取成功、欠款挂余额）。
     */
    @Transactional
    public boolean debitIfAbsentAllowNegative(String merchantId, long amountCents, String entryType,
                                              String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        return engine.debitIfAbsentAllowNegative(DOMAIN, merchantId, amountCents, entryType, refType, refId, remark);
    }

    /**
     * 某笔业务动作为商户钱包<b>实际扣减</b>的金额（分，正数）；没有流水就返回 0（展示口径 F-5）。
     */
    public long debitedCentsByRef(String merchantId, String refType, String refId) {
        if (merchantId == null) {
            return 0L;
        }
        return engine.debitedCentsByRef(DOMAIN, merchantId, refType, refId);
    }

    @Transactional
    public boolean debitIfAbsent(String merchantId, long amountCents, String entryType,
                                 String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        return engine.debitIfAbsent(DOMAIN, merchantId, amountCents, entryType, refType, refId, remark);
    }

    @Transactional
    public void credit(String merchantId, long amountCents, String entryType,
                       String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        engine.credit(DOMAIN, merchantId, amountCents, entryType, refType, refId, remark);
    }

    @Transactional
    public void debit(String merchantId, long amountCents, String entryType,
                      String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        engine.debit(DOMAIN, merchantId, amountCents, entryType, refType, refId, remark);
    }

    @Transactional
    public void freezeForWithdraw(String merchantId, long amountCents,
                                  String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        engine.freezeForWithdraw(DOMAIN, merchantId, amountCents, refType, refId, remark);
    }

    @Transactional
    public void releaseFrozen(String merchantId, long amountCents,
                              String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        engine.releaseFrozen(DOMAIN, merchantId, amountCents, refType, refId, remark);
    }

    @Transactional
    public void consumeFrozen(String merchantId, long amountCents,
                              String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        engine.consumeFrozen(DOMAIN, merchantId, amountCents, refType, refId, remark);
    }

    /**
     * V308：打款成功时拆分记账（净额记 WITHDRAW_PAID、手续费单列 WITHDRAW_FEE，两行相加==毛额）。
     */
    @Transactional
    public void consumeFrozenSplit(String merchantId, long netCents, long feeCents,
                                   String refType, String refId, String remark) {
        requireMerchantId(merchantId);
        engine.consumeFrozenSplit(DOMAIN, merchantId, netCents, feeCents, refType, refId, remark);
    }

    private static void requireMerchantId(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户 ID 无效");
        }
    }

    private static MerchantWalletAccount toAccount(WalletAccount w) {
        if (w == null) {
            return null;
        }
        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId(w.getOwnerId());
        account.setBalanceCents(w.getBalanceCents());
        account.setFrozenCents(w.getFrozenCents());
        account.setUpdatedAt(w.getUpdatedAt());
        return account;
    }

    private static MerchantWalletLedger toLedger(WalletLedger w) {
        if (w == null) {
            return null;
        }
        MerchantWalletLedger ledger = new MerchantWalletLedger();
        ledger.setLedgerId(w.getLedgerId());
        ledger.setMerchantId(w.getOwnerId());
        ledger.setMerchantName(w.getOwnerSnapshotName());
        ledger.setEntryType(w.getEntryType());
        ledger.setAmountCents(w.getAmountCents());
        ledger.setBalanceAfter(w.getBalanceAfter());
        ledger.setFrozenAfter(w.getFrozenAfter());
        ledger.setRefType(w.getRefType());
        ledger.setRefId(w.getRefId());
        ledger.setRemark(w.getRemark());
        ledger.setCreatedAt(w.getCreatedAt());
        return ledger;
    }
}
