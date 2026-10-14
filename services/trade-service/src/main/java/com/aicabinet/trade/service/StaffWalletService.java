package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.StaffWalletAccount;
import com.aicabinet.trade.domain.StaffWalletLedger;
import com.aicabinet.trade.mapper.StaffWalletAccountMapper;
import com.aicabinet.trade.mapper.StaffWalletLedgerMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 补货员钱包（V359，对齐老系统 CommissionAccount）：佣金出账时入账（资金视角），
 * 提现=线下打款后运营标记出账（debit，余额不足即失败——用户口径「没存钱就会提现失败」）。
 * 结构与 LineWalletService 同款（分布式锁 + 流水 + 余额），id 空间独立（user_info.userId）。
 */
@Service
public class StaffWalletService {

    private final StaffWalletAccountMapper accountMapper;
    private final StaffWalletLedgerMapper ledgerMapper;
    private final DistributedLockService distributedLockService;

    public StaffWalletService(StaffWalletAccountMapper accountMapper,
                              StaffWalletLedgerMapper ledgerMapper,
                              DistributedLockService distributedLockService) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public StaffWalletAccount ensureAccount(long staffUserId) {
        return runWithWalletLock(staffUserId, () -> doEnsureAccount(staffUserId));
    }

    /** 只读查询：不自动建户（列表 readOnly 事务防 INSERT 失败）。 */
    @Transactional(readOnly = true)
    public StaffWalletAccount findAccount(long staffUserId) {
        return accountMapper.selectById(staffUserId);
    }

    private StaffWalletAccount doEnsureAccount(long staffUserId) {
        StaffWalletAccount account = accountMapper.selectById(staffUserId);
        if (account != null) {
            return account;
        }
        account = new StaffWalletAccount();
        account.setStaffUserId(staffUserId);
        account.setBalanceCents(0L);
        account.setFrozenCents(0L);
        account.setUpdatedAt(Instant.now());
        accountMapper.insert(account);
        return account;
    }

    /**
     * 幂等入账（佣金）：相同 refType+refId 已存在流水则跳过。
     * @return true=本次新入账，false=已存在跳过
     */
    @Transactional
    public boolean creditIfAbsent(long staffUserId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return runWithWalletLock(staffUserId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(staffUserId, refType, refId).isPresent()) {
                return false;
            }
            doCredit(staffUserId, amountCents, entryType, refType, refId, remark);
            return true;
        });
    }

    /**
     * 标记线下打款出账（提现）：余额不足直接失败（对齐用户口径「没存钱就会提现失败」）。
     * 幂等：refType+refId 查重（同一笔提现标记重复提交只扣一次）。
     */
    @Transactional
    public boolean payoutIfAbsent(long staffUserId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return runWithWalletLock(staffUserId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(staffUserId, refType, refId).isPresent()) {
                return false;
            }
            StaffWalletAccount account = reload(staffUserId);
            if (value(account.getBalanceCents()) < amountCents) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "余额不足，提现失败");
            }
            long balance = value(account.getBalanceCents()) - amountCents;
            account.setBalanceCents(balance);
            account.setUpdatedAt(Instant.now());
            accountMapper.updateById(account);
            appendLedger(staffUserId, entryType, -amountCents, balance,
                    value(account.getFrozenCents()), refType, refId, remark);
            return true;
        });
    }

    private void doCredit(long staffUserId, long amountCents, String entryType,
                          String refType, String refId, String remark) {
        StaffWalletAccount account = reload(staffUserId);
        long balance = value(account.getBalanceCents()) + amountCents;
        account.setBalanceCents(balance);
        account.setUpdatedAt(Instant.now());
        accountMapper.updateById(account);
        appendLedger(staffUserId, entryType, amountCents, balance,
                value(account.getFrozenCents()), refType, refId, remark);
    }

    private StaffWalletAccount reload(long staffUserId) {
        StaffWalletAccount account = accountMapper.selectById(staffUserId);
        if (account == null) {
            account = doEnsureAccount(staffUserId);
        }
        return account;
    }

    private void appendLedger(long staffUserId, String entryType, long amountCents, long balanceAfter,
                              long frozenAfter, String refType, String refId, String remark) {
        StaffWalletLedger ledger = new StaffWalletLedger();
        ledger.setStaffUserId(staffUserId);
        ledger.setEntryType(trim(entryType, 32));
        ledger.setAmountCents(amountCents);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setFrozenAfter(frozenAfter);
        ledger.setRefType(trim(refType, 32));
        ledger.setRefId(trim(refId, 64));
        ledger.setRemark(trim(remark, 255));
        ledger.setCreatedAt(Instant.now());
        ledgerMapper.insert(ledger);
    }

    private <T> T runWithWalletLock(long staffUserId, Supplier<T> action) {
        String key = "staff-wallet:" + staffUserId;
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");
        }
    }

    private static long value(Long v) {
        return v == null ? 0L : v;
    }

    private static String trim(String v, int maxLen) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t.substring(0, Math.min(t.length(), maxLen));
    }
}
