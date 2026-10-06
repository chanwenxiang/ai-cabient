package com.aicabinet.trade.service;



import com.aicabinet.trade.domain.LineWalletAccount;

import com.aicabinet.trade.domain.LineWalletLedger;

import com.aicabinet.trade.mapper.LineWalletAccountMapper;

import com.aicabinet.trade.mapper.LineWalletLedgerMapper;

import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;



import java.time.Instant;



@Service

public class LineWalletService {



    private final LineWalletAccountMapper accountMapper;

    private final LineWalletLedgerMapper ledgerMapper;

    private final DistributedLockService distributedLockService;



    public LineWalletService(LineWalletAccountMapper accountMapper,

                             LineWalletLedgerMapper ledgerMapper,

                             DistributedLockService distributedLockService) {

        this.accountMapper = accountMapper;

        this.ledgerMapper = ledgerMapper;

        this.distributedLockService = distributedLockService;

    }



    @Transactional

    public LineWalletAccount ensureAccount(long managerId) {

        return runWithWalletLock(managerId, () -> doEnsureAccount(managerId));

    }

    /** 只读查询：不自动建户，避免列表等 readOnly 事务里 INSERT 失败。 */
    @Transactional(readOnly = true)
    public LineWalletAccount findAccount(long managerId) {
        return accountMapper.selectById(managerId);
    }

    private LineWalletAccount doEnsureAccount(long managerId) {

        LineWalletAccount account = accountMapper.selectById(managerId);

        if (account != null) {

            return account;

        }

        Instant now = Instant.now();

        account = new LineWalletAccount();

        account.setManagerId(managerId);

        account.setBalanceCents(0L);

        account.setFrozenCents(0L);

        account.setUpdatedAt(now);

        accountMapper.insert(account);

        return account;

    }



    @Transactional

    public void credit(long managerId, long amountCents, String entryType,

                       String refType, String refId, String remark) {

        runWithWalletLock(managerId, () -> {

            doCredit(managerId, amountCents, entryType, refType, refId, remark);

            return null;

        });

    }



    /**
     * 幂等入账：相同 refType+refId 已存在流水则跳过。
     * @return true 表示本次新入账，false 表示已存在跳过
     */
    @Transactional
    public boolean creditIfAbsent(long managerId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return runWithWalletLock(managerId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(managerId, refType, refId).isPresent()) {
                return false;
            }
            doCredit(managerId, amountCents, entryType, refType, refId, remark);
            return true;
        });
    }



    private void doCredit(long managerId, long amountCents, String entryType,

                          String refType, String refId, String remark) {

        requirePositive(amountCents);

        LineWalletAccount account = reload(managerId);

        long balance = value(account.getBalanceCents()) + amountCents;

        account.setBalanceCents(balance);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(managerId, entryType, amountCents,
                new LedgerLine.BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark));

    }



    @Transactional

    public void debit(long managerId, long amountCents, String entryType,

                      String refType, String refId, String remark) {

        runWithWalletLock(managerId, () -> {

            doDebit(managerId, amountCents, entryType, refType, refId, remark);

            return null;

        });

    }



    private void doDebit(long managerId, long amountCents, String entryType,

                         String refType, String refId, String remark) {

        requirePositive(amountCents);

        LineWalletAccount account = reload(managerId);

        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());

        if (available < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");

        }

        long balance = value(account.getBalanceCents()) - amountCents;

        account.setBalanceCents(balance);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(managerId, entryType, -amountCents,
                new LedgerLine.BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark));

    }



    @Transactional

    public void freezeForWithdraw(long managerId, long amountCents,

                                  String refType, String refId, String remark) {

        runWithWalletLock(managerId, () -> {

            doFreezeForWithdraw(managerId, amountCents, refType, refId, remark);

            return null;

        });

    }



    private void doFreezeForWithdraw(long managerId, long amountCents,

                                     String refType, String refId, String remark) {

        requirePositive(amountCents);

        LineWalletAccount account = reload(managerId);

        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());

        if (available < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");

        }

        long frozen = value(account.getFrozenCents()) + amountCents;

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(managerId, "WITHDRAW_FREEZE", -amountCents,
                new LedgerLine.BalanceSnapshot(value(account.getBalanceCents()), frozen), refType, refId, remark));

    }



    @Transactional

    public void releaseFrozen(long managerId, long amountCents,

                              String refType, String refId, String remark) {

        runWithWalletLock(managerId, () -> {

            doReleaseFrozen(managerId, amountCents, refType, refId, remark);

            return null;

        });

    }



    private void doReleaseFrozen(long managerId, long amountCents,

                                 String refType, String refId, String remark) {

        requirePositive(amountCents);

        LineWalletAccount account = reload(managerId);

        if (value(account.getFrozenCents()) < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结金额不足");

        }

        long frozen = value(account.getFrozenCents()) - amountCents;

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(managerId, "WITHDRAW_RELEASE", amountCents,
                new LedgerLine.BalanceSnapshot(value(account.getBalanceCents()), frozen), refType, refId, remark));

    }



    @Transactional

    public void consumeFrozen(long managerId, long amountCents,

                              String refType, String refId, String remark) {

        runWithWalletLock(managerId, () -> {

            doConsumeFrozen(managerId, amountCents, refType, refId, remark);

            return null;

        });

    }



    private void doConsumeFrozen(long managerId, long amountCents,

                                 String refType, String refId, String remark) {

        doConsumeFrozenSplit(managerId, amountCents, 0L, refType, refId, remark);

    }



    /**
     * V308：打款成功时<b>拆分记账</b> —— 净额记「已出款」，手续费单列一行。
     *
     * <p>与 {@link MerchantWalletService#consumeFrozenSplit} <b>完全同构</b>：
     * 余额与冻结<b>合计只扣一次 {@code net + fee}</b>（与拆分前扣毛额等价，不多扣不少扣），
     * 流水拆 {@code WITHDRAW_PAID -net} + {@code WITHDRAW_FEE -fee}，两行相加 == 原毛额。
     *
     * <p>🔴 <b>为什么线长侧也要拆</b>：口径不统一会产生「同一个{@code WITHDRAW_PAID}
     * 标签在商户侧是净额、在线长侧是毛额」的对账噩梦 —— 平台级汇总时无法直接相加。
     *
     * <p>⚠️ {@code feeCents = 0} 时<b>只记一行</b>，行为与拆分前完全一致。
     */
    @Transactional

    public void consumeFrozenSplit(long managerId, long netCents, long feeCents,

                                   String refType, String refId, String remark) {

        runWithWalletLock(managerId, () -> {

            doConsumeFrozenSplit(managerId, netCents, feeCents, refType, refId, remark);

            return null;

        });

    }



    private void doConsumeFrozenSplit(long managerId, long netCents, long feeCents,

                                       String refType, String refId, String remark) {

        long net = Math.max(0L, netCents);

        long fee = Math.max(0L, feeCents);

        long gross = net + fee;

        // 🔴 溢出保护：net+fee 理论上来自同一笔毛额的拆分，但上游误传两个大数时仍要挡住
        if (gross < 0L) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额不合法");

        }

        requirePositive(gross);

        LineWalletAccount account = reload(managerId);

        if (value(account.getBalanceCents()) < gross || value(account.getFrozenCents()) < gross) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结或余额不足");

        }

        long balance = value(account.getBalanceCents()) - gross;

        long frozen = value(account.getFrozenCents()) - gross;

        account.setBalanceCents(balance);

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        if (net > 0L) {

            // 快照记「扣手续费之前」，两行的 balance_after 连起来才是完整轨迹
            appendLedger(new LedgerLine(managerId, "WITHDRAW_PAID", -net,

                    new LedgerLine.BalanceSnapshot(balance + fee, frozen + fee), refType, refId, remark));

        }

        if (fee > 0L) {

            appendLedger(new LedgerLine(managerId, "WITHDRAW_FEE", -fee,

                    new LedgerLine.BalanceSnapshot(balance, frozen), refType, refId, remark));

        }

    }



    static String walletLockKey(long managerId) {

        return "line:wallet:" + managerId;

    }



    private <T> T runWithWalletLock(long managerId, java.util.function.Supplier<T> action) {

        if (!distributedLockService.tryLock(walletLockKey(managerId), 60, 5)) {

            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重试");

        }

        try {

            return action.get();

        } catch (ResponseStatusException e) {

            throw e;

        } catch (Exception e) {

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);

        } finally {

            distributedLockService.unlock(walletLockKey(managerId));

        }

    }



    private LineWalletAccount reload(long managerId) {

        doEnsureAccount(managerId);

        return accountMapper.findByIdForUpdate(managerId)

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "线长钱包不存在"));

    }



    private void appendLedger(LedgerLine line) {

        LineWalletLedger ledger = new LineWalletLedger();

        ledger.setManagerId(line.managerId());

        ledger.setEntryType(line.entryType());

        ledger.setAmountCents(line.amountCents());

        ledger.setBalanceAfter(line.balances().balanceAfter());

        ledger.setFrozenAfter(line.balances().frozenAfter());

        ledger.setRefType(trim(line.refType(), 32));

        ledger.setRefId(trim(line.refId(), 64));

        ledger.setRemark(trim(line.remark(), 255));

        ledger.setCreatedAt(Instant.now());

        ledgerMapper.insert(ledger);

    }

    private record LedgerLine(long managerId, String entryType, long amountCents, BalanceSnapshot balances,
                              String refType, String refId, String remark) {
        private record BalanceSnapshot(long balanceAfter, long frozenAfter) {}
    }



    private static void requirePositive(long amountCents) {

        if (amountCents <= 0) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");

        }

    }



    private static long value(Long value) {

        return value == null ? 0L : value;

    }



    private static String trim(String value, int maxLen) {

        if (value == null) {

            return null;

        }

        String trimmed = value.trim();

        if (trimmed.isEmpty()) {

            return null;

        }

        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;

    }

}


