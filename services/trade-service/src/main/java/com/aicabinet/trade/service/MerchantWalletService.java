package com.aicabinet.trade.service;



import com.aicabinet.trade.domain.MerchantWalletAccount;

import com.aicabinet.trade.domain.MerchantWalletLedger;

import com.aicabinet.trade.mapper.MerchantMapper;

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

    private final MerchantMapper merchantMapper;

    private final DistributedLockService distributedLockService;



    public MerchantWalletService(MerchantWalletAccountMapper accountMapper,

                                 MerchantWalletLedgerMapper ledgerMapper,

                                 MerchantMapper merchantMapper,

                                 DistributedLockService distributedLockService) {

        this.accountMapper = accountMapper;

        this.ledgerMapper = ledgerMapper;

        this.merchantMapper = merchantMapper;

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
                                          ReverseCreditCommand command) {
        return runWithWalletLock(merchantId, () -> {

            requireMerchantId(merchantId);

            requirePositive(amountCents);

            if (command.originalRefType() == null || command.originalRefId() == null
                    || ledgerMapper.findByRef(merchantId, command.originalRefType(), command.originalRefId()).isEmpty()) {
                return false;
            }
            if (command.reverseRefType() != null && command.reverseRefId() != null
                    && ledgerMapper.findByRef(merchantId, command.reverseRefType(), command.reverseRefId()).isPresent()) {
                return false;
            }
            doDebit(merchantId, amountCents, entryType, command.reverseRefType(), command.reverseRefId(), command.remark());
            return true;
        });
    }

    public record ReverseCreditCommand(
            String reverseRefType, String reverseRefId, String originalRefType, String originalRefId, String remark) {}



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

        doConsumeFrozenSplit(merchantId, amountCents, 0L, refType, refId, remark);

    }

    /**
     * V308：打款成功时<b>拆分记账</b> —— 净额记「已出款」，手续费单列一行。
     *
     * <p>🔴 <b>先纠正一个常见误解</b>：手续费<b>本来就已被扣走</b>。申请时冻结的是<b>毛额</b>，
     * 打款成功扣的也是毛额，而渠道实际只发 {@code netCents = 毛额 − 手续费}，
     * 差额天然留在平台。真正缺的不是「把钱扣掉」，而是<b>可对账性</b>：
     * <ul>
     *   <li>拆分前商户流水只有一条 {@code WITHDRAW_PAID -毛额} ⇒ 商户对账单看不懂
     *       「申请 100 元，为什么被扣的手续费查不到这一笔」；</li>
     *   <li>平台侧也没有任何一行能汇总「今日手续费收入」⇒ 无法与渠道流水对账。</li>
     * </ul>
     *
     * <p><b>本方法的口径</b>：余额与冻结<b>合计只扣一次 {@code netCents + feeCents}</b>
     * （与拆分前扣毛额<b>完全等价</b>，不多扣也不少扣），但流水拆两行：
     * {@code WITHDRAW_PAID -netCents} + {@code WITHDRAW_FEE -feeCents}。
     * 两行相加 == 原毛额 ⇒ 账单总和不变，审计口径可验证。
     *
     * <p>⚠️ {@code feeCents = 0} 时<b>只记一行</b>（不留 0 元流水噪音），行为与拆分前完全一致。
     */
    @Transactional

    public void consumeFrozenSplit(String merchantId, long netCents, long feeCents,

                                   String refType, String refId, String remark) {

        runWithWalletLock(merchantId, () -> {

            doConsumeFrozenSplit(merchantId, netCents, feeCents, refType, refId, remark);

            return null;

        });

    }

    private void doConsumeFrozenSplit(String merchantId, long netCents, long feeCents,

                                       String refType, String refId, String remark) {

        long net = Math.max(0L, netCents);

        long fee = Math.max(0L, feeCents);

        long gross = net + fee;

        // 🔴 溢出保护：net+fee 理论上来自同一笔毛额的拆分，但上游误传两个大数时仍要挡住
        if (gross < 0L) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额不合法");

        }

        requirePositive(gross);

        MerchantWalletAccount account = reload(merchantId);

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
            appendLedger(new LedgerLine(merchantId, "WITHDRAW_PAID", -net,

                    new LedgerLine.BalanceSnapshot(balance + fee, frozen + fee), refType, refId, remark));

        }

        if (fee > 0L) {

            appendLedger(new LedgerLine(merchantId, "WITHDRAW_FEE", -fee,

                    new LedgerLine.BalanceSnapshot(balance, frozen), refType, refId, remark));

        }

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

        var merchant = merchantMapper.selectById(line.merchantId());

        if (merchant != null) {

            ledger.setMerchantName(merchant.getMerchantName());

        }

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


