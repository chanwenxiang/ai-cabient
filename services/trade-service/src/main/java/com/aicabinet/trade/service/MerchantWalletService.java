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

    public MerchantWalletService(MerchantWalletAccountMapper accountMapper,
                                 MerchantWalletLedgerMapper ledgerMapper) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
    }

    @Transactional
    public MerchantWalletAccount ensureAccount(String merchantId) {
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
        requireMerchantId(merchantId);
        requirePositive(amountCents);
        if (refType != null && refId != null && ledgerMapper.findByRef(merchantId, refType, refId).isPresent()) {
            return false;
        }
        credit(merchantId, amountCents, entryType, refType, refId, remark);
        return true;
    }

    @Transactional
    public boolean reverseCreditIfPresent(String merchantId, long amountCents, String entryType,
                                          String reverseRefType, String reverseRefId,
                                          String originalRefType, String originalRefId, String remark) {
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
        debit(merchantId, amountCents, entryType, reverseRefType, reverseRefId, remark);
        return true;
    }

    @Transactional
    public void credit(String merchantId, long amountCents, String entryType,
                       String refType, String refId, String remark) {
        requirePositive(amountCents);
        MerchantWalletAccount account = reload(merchantId);
        long balance = value(account.getBalanceCents()) + amountCents;
        account.setBalanceCents(balance);
        account.setUpdatedAt(Instant.now());
        accountMapper.updateById(account);
        appendLedger(merchantId, entryType, amountCents, balance, value(account.getFrozenCents()),
                refType, refId, remark);
    }

    @Transactional
    public void debit(String merchantId, long amountCents, String entryType,
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
        appendLedger(merchantId, entryType, -amountCents, balance, value(account.getFrozenCents()),
                refType, refId, remark);
    }

    @Transactional
    public void freezeForWithdraw(String merchantId, long amountCents,
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
        appendLedger(merchantId, "WITHDRAW_FREEZE", -amountCents, value(account.getBalanceCents()), frozen,
                refType, refId, remark);
    }

    @Transactional
    public void releaseFrozen(String merchantId, long amountCents,
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
        appendLedger(merchantId, "WITHDRAW_RELEASE", amountCents, value(account.getBalanceCents()), frozen,
                refType, refId, remark);
    }

    @Transactional
    public void consumeFrozen(String merchantId, long amountCents,
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
        appendLedger(merchantId, "WITHDRAW_PAID", -amountCents, balance, frozen, refType, refId, remark);
    }

    private MerchantWalletAccount reload(String merchantId) {
        ensureAccount(merchantId);
        return accountMapper.selectById(merchantId);
    }

    private void appendLedger(String merchantId, String entryType, long amountCents, long balanceAfter, long frozenAfter,
                              String refType, String refId, String remark) {
        MerchantWalletLedger ledger = new MerchantWalletLedger();
        ledger.setMerchantId(merchantId);
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
