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



    private void doCredit(long managerId, long amountCents, String entryType,

                          String refType, String refId, String remark) {

        requirePositive(amountCents);

        LineWalletAccount account = reload(managerId);

        long balance = value(account.getBalanceCents()) + amountCents;

        account.setBalanceCents(balance);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(managerId, entryType, amountCents, balance, value(account.getFrozenCents()),

                refType, refId, remark);

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

        appendLedger(managerId, entryType, -amountCents, balance, value(account.getFrozenCents()),

                refType, refId, remark);

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

        appendLedger(managerId, "WITHDRAW_FREEZE", -amountCents, value(account.getBalanceCents()), frozen,

                refType, refId, remark);

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

        appendLedger(managerId, "WITHDRAW_RELEASE", amountCents, value(account.getBalanceCents()), frozen,

                refType, refId, remark);

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

        requirePositive(amountCents);

        LineWalletAccount account = reload(managerId);

        if (value(account.getBalanceCents()) < amountCents || value(account.getFrozenCents()) < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结或余额不足");

        }

        long balance = value(account.getBalanceCents()) - amountCents;

        long frozen = value(account.getFrozenCents()) - amountCents;

        account.setBalanceCents(balance);

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(managerId, "WITHDRAW_PAID", -amountCents, balance, frozen, refType, refId, remark);

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



    private void appendLedger(long managerId, String entryType, long amountCents, long balanceAfter, long frozenAfter,

                              String refType, String refId, String remark) {

        LineWalletLedger ledger = new LineWalletLedger();

        ledger.setManagerId(managerId);

        ledger.setEntryType(entryType);

        ledger.setAmountCents(amountCents);

        ledger.setBalanceAfter(balanceAfter);

        ledger.setFrozenAfter(frozenAfter);

        ledger.setRefType(trim(refType, 32));

        ledger.setRefId(trim(refId, 64));

        ledger.setRemark(trim(remark, 255));

        ledger.setCreatedAt(Instant.now());

        ledgerMapper.insert(ledger);

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


