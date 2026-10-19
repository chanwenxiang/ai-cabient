package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWalletLedger;
import com.aicabinet.trade.domain.WalletAccount;
import com.aicabinet.trade.domain.WalletLedger;
import com.aicabinet.trade.service.wallet.WalletDomain;
import com.aicabinet.trade.service.wallet.WalletEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 线长钱包 facade（V133）。V388 三域物理统一后仅保留 facade 契约
 * （签名、{@code long managerId} 入参、{@link LineWalletAccount} 返回类型不变），
 * 资金写路径委托 {@link WalletEngine}（domain=LINE）。线长有完整冻结生命周期（提现冻结/释放/拆分记账）。
 */
@Service
public class LineWalletService {

    private static final WalletDomain DOMAIN = WalletDomain.LINE;

    private final WalletEngine engine;

    public LineWalletService(WalletEngine engine) {
        this.engine = engine;
    }

    @Transactional
    public LineWalletAccount ensureAccount(long managerId) {
        return toAccount(engine.ensureAccount(DOMAIN, key(managerId)));
    }

    /** 只读查询：不自动建户，避免列表等 readOnly 事务里 INSERT 失败。 */
    @Transactional(readOnly = true)
    public LineWalletAccount findAccount(long managerId) {
        return toAccount(engine.findAccount(DOMAIN, key(managerId)));
    }

    /** 某线长最近 N 条流水（created_at 倒序），映射回 {@link LineWalletLedger} 供线长管理视图复用。 */
    @Transactional(readOnly = true)
    public List<LineWalletLedger> listRecentLedger(long managerId, int limit) {
        return engine.listLedgerByCreatedAtDesc(DOMAIN, key(managerId), limit).stream()
                .map(LineWalletService::toLedger)
                .toList();
    }

    @Transactional
    public void credit(long managerId, long amountCents, String entryType,
                       String refType, String refId, String remark) {
        engine.credit(DOMAIN, key(managerId), amountCents, entryType, refType, refId, remark);
    }

    /**
     * 幂等入账：相同 refType+refId 已存在流水则跳过。
     * @return true 表示本次新入账，false 表示已存在跳过
     */
    @Transactional
    public boolean creditIfAbsent(long managerId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return engine.creditIfAbsent(DOMAIN, key(managerId), amountCents, entryType, refType, refId, remark);
    }

    /**
     * 幂等扣款且允许扣成负数（CB-040 佣金随退款冲正）。refType+refId 查重防重复扣。
     */
    @Transactional
    public boolean debitIfAbsentAllowNegative(long managerId, long amountCents, String entryType,
                                              String refType, String refId, String remark) {
        return engine.debitIfAbsentAllowNegative(DOMAIN, key(managerId), amountCents, entryType, refType, refId, remark);
    }

    @Transactional
    public void debit(long managerId, long amountCents, String entryType,
                      String refType, String refId, String remark) {
        engine.debit(DOMAIN, key(managerId), amountCents, entryType, refType, refId, remark);
    }

    @Transactional
    public void freezeForWithdraw(long managerId, long amountCents,
                                  String refType, String refId, String remark) {
        engine.freezeForWithdraw(DOMAIN, key(managerId), amountCents, refType, refId, remark);
    }

    @Transactional
    public void releaseFrozen(long managerId, long amountCents,
                              String refType, String refId, String remark) {
        engine.releaseFrozen(DOMAIN, key(managerId), amountCents, refType, refId, remark);
    }

    @Transactional
    public void consumeFrozen(long managerId, long amountCents,
                              String refType, String refId, String remark) {
        engine.consumeFrozen(DOMAIN, key(managerId), amountCents, refType, refId, remark);
    }

    /**
     * V308：打款成功时拆分记账（净额 + 手续费两行，合计==毛额）。与商户侧完全同构。
     */
    @Transactional
    public void consumeFrozenSplit(long managerId, long netCents, long feeCents,
                                   String refType, String refId, String remark) {
        engine.consumeFrozenSplit(DOMAIN, key(managerId), netCents, feeCents, refType, refId, remark);
    }

    private static String key(long managerId) {
        return String.valueOf(managerId);
    }

    private static LineWalletAccount toAccount(WalletAccount w) {
        if (w == null) {
            return null;
        }
        LineWalletAccount account = new LineWalletAccount();
        account.setManagerId(Long.parseLong(w.getOwnerId()));
        account.setBalanceCents(w.getBalanceCents());
        account.setFrozenCents(w.getFrozenCents());
        account.setUpdatedAt(w.getUpdatedAt());
        return account;
    }

    private static LineWalletLedger toLedger(WalletLedger w) {
        if (w == null) {
            return null;
        }
        LineWalletLedger ledger = new LineWalletLedger();
        ledger.setLedgerId(w.getLedgerId());
        ledger.setManagerId(Long.parseLong(w.getOwnerId()));
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
