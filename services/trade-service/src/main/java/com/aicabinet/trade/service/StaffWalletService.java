package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.StaffWalletAccount;
import com.aicabinet.trade.domain.StaffWalletLedger;
import com.aicabinet.trade.domain.WalletAccount;
import com.aicabinet.trade.domain.WalletLedger;
import com.aicabinet.trade.service.wallet.WalletDomain;
import com.aicabinet.trade.service.wallet.WalletEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 补货员钱包 facade（V359，对齐老系统 CommissionAccount）：佣金出账时入账（资金视角），
 * 提现=线下打款后运营标记出账（余额不足即失败——用户口径「没存钱就会提现失败」）。
 *
 * <p>V388 三域物理统一后，本类<b>只保留 facade 契约</b>（方法签名、{@code long staffUserId} 入参、
 * {@link StaffWalletAccount}/{@link StaffWalletLedger} 返回类型不变，控制器/openapi 无感），
 * 全部资金写路径委托给 {@link WalletEngine}（domain=STAFF，owner_id=数值转字符串）。
 * 补货员无冻结业务：frozen 恒 0，提现走余额直扣 {@code payoutIfAbsent}。
 */
@Service
public class StaffWalletService {

    private static final WalletDomain DOMAIN = WalletDomain.STAFF;

    private final WalletEngine engine;

    public StaffWalletService(WalletEngine engine) {
        this.engine = engine;
    }

    @Transactional
    public StaffWalletAccount ensureAccount(long staffUserId) {
        return toAccount(engine.ensureAccount(DOMAIN, key(staffUserId)));
    }

    /** 只读查询：不自动建户（列表 readOnly 事务防 INSERT 失败）。 */
    @Transactional(readOnly = true)
    public StaffWalletAccount findAccount(long staffUserId) {
        return toAccount(engine.findAccount(DOMAIN, key(staffUserId)));
    }

    /**
     * 幂等入账（佣金）：相同 refType+refId 已存在流水则跳过。
     * @return true=本次新入账，false=已存在跳过
     */
    @Transactional
    public boolean creditIfAbsent(long staffUserId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return engine.creditIfAbsent(DOMAIN, key(staffUserId), amountCents, entryType, refType, refId, remark);
    }

    /**
     * 标记线下打款出账（提现）：余额不足直接失败。幂等 refType+refId 查重。
     */
    @Transactional
    public boolean payoutIfAbsent(long staffUserId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return engine.payoutIfAbsent(DOMAIN, key(staffUserId), amountCents, entryType, refType, refId, remark);
    }

    /**
     * 幂等扣款且允许扣成负数（CB-040 佣金随退款冲正）。refType+refId 查重防重复扣。
     */
    @Transactional
    public boolean debitIfAbsentAllowNegative(long staffUserId, long amountCents, String entryType,
                                              String refType, String refId, String remark) {
        return engine.debitIfAbsentAllowNegative(DOMAIN, key(staffUserId), amountCents, entryType, refType, refId, remark);
    }

    // ==== 运营端查询（架构门禁禁 Controller 直调 Mapper）====

    /** 补货员钱包余额列表（余额降序）。 */
    @Transactional(readOnly = true)
    public List<StaffWalletAccount> listWallets() {
        return engine.listWallets(DOMAIN).stream().map(StaffWalletService::toAccount).toList();
    }

    /** 某补货员钱包流水（新在前）。 */
    @Transactional(readOnly = true)
    public List<StaffWalletLedger> listLedger(long staffUserId) {
        return engine.listLedger(DOMAIN, key(staffUserId)).stream().map(StaffWalletService::toLedger).toList();
    }

    private static String key(long staffUserId) {
        return String.valueOf(staffUserId);
    }

    private static StaffWalletAccount toAccount(WalletAccount w) {
        if (w == null) {
            return null;
        }
        StaffWalletAccount account = new StaffWalletAccount();
        account.setStaffUserId(Long.parseLong(w.getOwnerId()));
        account.setBalanceCents(w.getBalanceCents());
        account.setUpdatedAt(w.getUpdatedAt());
        return account;
    }

    private static StaffWalletLedger toLedger(WalletLedger w) {
        StaffWalletLedger ledger = new StaffWalletLedger();
        ledger.setLedgerId(w.getLedgerId());
        ledger.setStaffUserId(Long.parseLong(w.getOwnerId()));
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
