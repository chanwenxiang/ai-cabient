package com.aicabinet.trade.service;



import com.aicabinet.trade.domain.MerchantWalletAccount;

import com.aicabinet.trade.domain.MerchantWalletLedger;

import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;

import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;

import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;



import java.time.Instant;



@Service

public class MerchantWalletService {



    private final MerchantWalletAccountMapper accountMapper;

    private final MerchantWalletLedgerMapper ledgerMapper;

    private final DistributedLockService distributedLockService;



    public MerchantWalletService(MerchantWalletAccountMapper accountMapper,

                                 MerchantWalletLedgerMapper ledgerMapper,

                                 DistributedLockService distributedLockService) {

        this.accountMapper = accountMapper;

        this.ledgerMapper = ledgerMapper;

        this.distributedLockService = distributedLockService;

    }



    @Transactional

    public MerchantWalletAccount ensureAccount(String merchantId) {

        return runWithWalletLock(merchantId, () -> doEnsureAccount(merchantId));

    }



    private MerchantWalletAccount doEnsureAccount(String merchantId) {

        requireMerchantId(merchantId);

        MerchantWalletAccount account = accountMapper.selectById(merchantId);

        if (account != null) {

            return account;

        }

        Instant now = Instant.now();

        account = new MerchantWalletAccount();

        account.setMerchantId(merchantId);

        account.setBalanceCents(0L);

        account.setFrozenCents(0L);

        account.setUpdatedAt(now);

        accountMapper.insert(account);

        return account;

    }



    @Transactional

    public boolean creditIfAbsent(String merchantId, long amountCents, String entryType,

                                  String refType, String refId, String remark) {

        return runWithWalletLock(merchantId, () -> {

            requireMerchantId(merchantId);

            requirePositive(amountCents);

            if (refType != null && refId != null && ledgerMapper.findByRef(merchantId, refType, refId).isPresent()) {

                return false;

            }

            doCredit(merchantId, amountCents, entryType, refType, refId, remark);

            return true;

        });

    }



    @Transactional

    public boolean reverseCreditIfPresent(String merchantId, long amountCents, String entryType,

                                          String reverseRefType, String reverseRefId,

                                          String originalRefType, String originalRefId, String remark) {

        return runWithWalletLock(merchantId, () -> {

            requireMerchantId(merchantId);

            requirePositive(amountCents);

            if (originalRefType == null || originalRefId == null

                    || ledgerMapper.findByRef(merchantId, originalRefType, originalRefId).isEmpty()) {

                return false;

            }

            if (reverseRefType != null && reverseRefId != null

                    && ledgerMapper.findByRef(merchantId, reverseRefType, reverseRefId).isPresent()) {

                return false;

            }

            doDebit(merchantId, amountCents, entryType, reverseRefType, reverseRefId, remark);

            return true;

        });

    }



    /** 幂等扣款：同一 ref 仅记一次账。 */

    @Transactional

    public boolean debitIfAbsent(String merchantId, long amountCents, String entryType,

                                 String refType, String refId, String remark) {

        return runWithWalletLock(merchantId, () -> {

            requireMerchantId(merchantId);

            requirePositive(amountCents);

            if (refType != null && refId != null && ledgerMapper.findByRef(merchantId, refType, refId).isPresent()) {

                return false;

            }

            doDebit(merchantId, amountCents, entryType, refType, refId, remark);

            return true;

        });

    }



    @Transactional

    public void credit(String merchantId, long amountCents, String entryType,

                       String refType, String refId, String remark) {

        runWithWalletLock(merchantId, () -> {

            doCredit(merchantId, amountCents, entryType, refType, refId, remark);

            return null;

        });

    }



    private void doCredit(String merchantId, long amountCents, String entryType,

                          String refType, String refId, String remark) {

        requirePositive(amountCents);

        MerchantWalletAccount account = reload(merchantId);

        long balance = value(account.getBalanceCents()) + amountCents;

        account.setBalanceCents(balance);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(merchantId, entryType, amountCents,
                new LedgerLine.BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark));

    }



    @Transactional

    public void debit(String merchantId, long amountCents, String entryType,

                      String refType, String refId, String remark) {

        runWithWalletLock(merchantId, () -> {

            doDebit(merchantId, amountCents, entryType, refType, refId, remark);

            return null;

        });

    }



    private void doDebit(String merchantId, long amountCents, String entryType,

                         String refType, String refId, String remark) {

        requirePositive(amountCents);

        MerchantWalletAccount account = reload(merchantId);

        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());

        if (available < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");

        }

        long balance = value(account.getBalanceCents()) - amountCents;

        account.setBalanceCents(balance);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(merchantId, entryType, -amountCents,
                new LedgerLine.BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark));

    }



    @Transactional

    public void freezeForWithdraw(String merchantId, long amountCents,

                                  String refType, String refId, String remark) {

        runWithWalletLock(merchantId, () -> {

            doFreezeForWithdraw(merchantId, amountCents, refType, refId, remark);

            return null;

        });

    }



    private void doFreezeForWithdraw(String merchantId, long amountCents,

                                     String refType, String refId, String remark) {

        requirePositive(amountCents);

        MerchantWalletAccount account = reload(merchantId);

        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());

        if (available < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");

        }

        long frozen = value(account.getFrozenCents()) + amountCents;

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(merchantId, "WITHDRAW_FREEZE", -amountCents,
                new LedgerLine.BalanceSnapshot(value(account.getBalanceCents()), frozen), refType, refId, remark));

    }



    @Transactional

    public void releaseFrozen(String merchantId, long amountCents,

                              String refType, String refId, String remark) {

        runWithWalletLock(merchantId, () -> {

            doReleaseFrozen(merchantId, amountCents, refType, refId, remark);

            return null;

        });

    }



    private void doReleaseFrozen(String merchantId, long amountCents,

                                 String refType, String refId, String remark) {

        requirePositive(amountCents);

        MerchantWalletAccount account = reload(merchantId);

        if (value(account.getFrozenCents()) < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结金额不足");

        }

        long frozen = value(account.getFrozenCents()) - amountCents;

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(merchantId, "WITHDRAW_RELEASE", amountCents,
                new LedgerLine.BalanceSnapshot(value(account.getBalanceCents()), frozen), refType, refId, remark));

    }



    @Transactional

    public void consumeFrozen(String merchantId, long amountCents,

                              String refType, String refId, String remark) {

        runWithWalletLock(merchantId, () -> {

            doConsumeFrozen(merchantId, amountCents, refType, refId, remark);

            return null;

        });

    }



    private void doConsumeFrozen(String merchantId, long amountCents,

                                 String refType, String refId, String remark) {

        requirePositive(amountCents);

        MerchantWalletAccount account = reload(merchantId);

        if (value(account.getBalanceCents()) < amountCents || value(account.getFrozenCents()) < amountCents) {

            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结或余额不足");

        }

        long balance = value(account.getBalanceCents()) - amountCents;

        long frozen = value(account.getFrozenCents()) - amountCents;

        account.setBalanceCents(balance);

        account.setFrozenCents(frozen);

        account.setUpdatedAt(Instant.now());

        accountMapper.updateById(account);

        appendLedger(new LedgerLine(merchantId, "WITHDRAW_PAID", -amountCents,
                new LedgerLine.BalanceSnapshot(balance, frozen), refType, refId, remark));

    }



    static String walletLockKey(String merchantId) {

        return "merchant:wallet:" + merchantId;

    }



    private <T> T runWithWalletLock(String merchantId, java.util.function.Supplier<T> action) {

        if (!distributedLockService.tryLock(walletLockKey(merchantId), 60, 5)) {

            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重试");

        }

        try {

            return action.get();

        } catch (ResponseStatusException e) {

            throw e;

        } catch (Exception e) {

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);

        } finally {

            distributedLockService.unlock(walletLockKey(merchantId));

        }

    }



    private MerchantWalletAccount reload(String merchantId) {

        doEnsureAccount(merchantId);

        return accountMapper.findByIdForUpdate(merchantId)

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "商户钱包不存在"));

    }



    private void appendLedger(LedgerLine line) {

        MerchantWalletLedger ledger = new MerchantWalletLedger();

        ledger.setMerchantId(line.merchantId());

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

    private record LedgerLine(String merchantId, String entryType, long amountCents, BalanceSnapshot balances,
                              String refType, String refId, String remark) {
        private record BalanceSnapshot(long balanceAfter, long frozenAfter) {}
    }



    private static void requireMerchantId(String merchantId) {

        if (merchantId == null || merchantId.isBlank()) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户 ID 无效");

        }

    }



    private static void requirePositive(long amountCents) {

        if (amountCents <= 0) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");

        }

    }



    private static long value(Long value) {

        return value == null ? 0L : value;

    }



    private static String trim(String value, int max) {

        if (value == null) {

            return null;

        }

        String trimmed = value.trim();

        if (trimmed.isEmpty()) {

            return null;

        }

        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);

    }

}


