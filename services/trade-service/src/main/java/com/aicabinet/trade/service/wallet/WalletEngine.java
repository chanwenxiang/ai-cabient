package com.aicabinet.trade.service.wallet;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.WalletAccount;
import com.aicabinet.trade.domain.WalletLedger;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.WalletAccountMapper;
import com.aicabinet.trade.mapper.WalletLedgerMapper;
import com.aicabinet.trade.service.DistributedLockService;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 三域钱包<b>写原语引擎</b>（V388 物理统一后的唯一资金写入口）。
 *
 * <p>把 Merchant/Line/Staff 三个 {@code *WalletService} 里同构的「分布式锁 + 行锁 + 幂等查重 +
 * 余额/冻结增减 + 流水记账」收敛为一份实现，按 {@link WalletDomain}+{@code ownerId} 参数化。
 * 能力差异（商户可用余额扣、补货员余额直扣提现、冻结生命周期仅商户/线长）通过<b>不同方法</b>表达，
 * 而非 {@code if(domain)} 分支——每个域只调用它本就拥有的能力。
 *
 * <p>🔴 锁串由 {@link WalletDomain#lockKey} 逐字沿用统一前的原语，且与各提现服务的
 * {@code runWith*WalletLock} 同一把可重入锁；改串会击穿钱包↔提现互斥 ⇒ 双扣。
 */
@Service
public class WalletEngine {

    private static final Logger log = LoggerFactory.getLogger(WalletEngine.class);

    private final WalletAccountMapper accountMapper;
    private final WalletLedgerMapper ledgerMapper;
    private final MerchantMapper merchantMapper;
    private final DistributedLockService distributedLockService;

    /**
     * 会改动「冻结占用额」的流水类型：冻结记负、释放/扣款记正 ⇒ 本单占用额 = {@code -Σamount}。
     * 新增任何动冻结的 entryType 必须加进来，否则该单的幂等判据会把占用额算小（多释放）。
     */
    private static final Set<String> FROZEN_ENTRY_TYPES =
            Set.of("WITHDRAW_FREEZE", "WITHDRAW_RELEASE", "WITHDRAW_PAID", "WITHDRAW_FEE");

    public WalletEngine(WalletAccountMapper accountMapper,
                        WalletLedgerMapper ledgerMapper,
                        MerchantMapper merchantMapper,
                        DistributedLockService distributedLockService) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.merchantMapper = merchantMapper;
        this.distributedLockService = distributedLockService;
    }

    // ==== 建户 / 读 ====

    @Transactional
    public WalletAccount ensureAccount(WalletDomain domain, String ownerId) {
        return runWithWalletLock(domain, ownerId, () -> doEnsureAccount(domain, ownerId));
    }

    /** 只读查询：不自动建户（readOnly 事务防 INSERT 失败）。 */
    @Transactional(readOnly = true)
    public WalletAccount findAccount(WalletDomain domain, String ownerId) {
        return accountMapper.findByOwnerRaw(domain.column(), ownerId);
    }

    @Transactional(readOnly = true)
    public List<WalletAccount> listWallets(WalletDomain domain) {
        return accountMapper.selectList(Wrappers.<WalletAccount>lambdaQuery()
                .eq(WalletAccount::getDomain, domain.column())
                .orderByDesc(WalletAccount::getBalanceCents));
    }

    @Transactional(readOnly = true)
    public List<WalletLedger> listLedger(WalletDomain domain, String ownerId) {
        return ledgerMapper.listByOwnerOrderByLedgerIdDesc(domain.column(), ownerId);
    }

    @Transactional(readOnly = true)
    public List<WalletLedger> listLedgerByCreatedAtDesc(WalletDomain domain, String ownerId, int limit) {
        return ledgerMapper.listByOwnerOrderByCreatedAtDesc(domain.column(), ownerId, limit);
    }

    /** 域内全量某类流水求和（绝对值口径，[from,to) 左闭右开）——对账/运营汇总用，禁止给单 owner 算账。 */
    @Transactional(readOnly = true)
    public long sumAmountByEntryTypeBetween(WalletDomain domain, String entryType, Instant from, Instant to) {
        return ledgerMapper.sumAmountByEntryTypeBetween(domain.column(), entryType, from, to);
    }

    /** 自某时刻起累计（无上界）——「今日/近 N 日手续费收入」滚动口径。 */
    @Transactional(readOnly = true)
    public long sumAmountByEntryTypeSince(WalletDomain domain, String entryType, Instant since) {
        return ledgerMapper.sumAmountByEntryTypeSince(domain.column(), entryType, since);
    }

    private WalletAccount doEnsureAccount(WalletDomain domain, String ownerId) {
        WalletAccount account = accountMapper.findByOwnerRaw(domain.column(), ownerId);
        if (account != null) {
            return account;
        }
        account = new WalletAccount();
        account.setDomain(domain.column());
        account.setOwnerId(ownerId);
        account.setBalanceCents(0L);
        account.setFrozenCents(0L);
        account.setUpdatedAt(Instant.now());
        accountMapper.insert(account);
        return account;
    }

    // ==== 入账 ====

    /** 非幂等入账（商户/线长历史口径保留：每次调用都记一笔）。 */
    @Transactional
    public void credit(WalletDomain domain, String ownerId, long amountCents, String entryType,
                       String refType, String refId, String remark) {
        runWithWalletLock(domain, ownerId, () -> {
            doCredit(domain, ownerId, amountCents, entryType, refType, refId, remark);
            return null;
        });
    }

    /** 幂等入账：相同 refType+refId 已存在流水则跳过。@return true=本次新入账。 */
    @Transactional
    public boolean creditIfAbsent(WalletDomain domain, String ownerId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(domain.column(), ownerId, refType, refId).isPresent()) {
                return false;
            }
            doCredit(domain, ownerId, amountCents, entryType, refType, refId, remark);
            return true;
        });
    }

    private void doCredit(WalletDomain domain, String ownerId, long amountCents, String entryType,
                          String refType, String refId, String remark) {
        requirePositive(amountCents);
        WalletAccount account = reload(domain, ownerId);
        long balance = value(account.getBalanceCents()) + amountCents;
        persistBalances(domain, ownerId, balance, value(account.getFrozenCents()));
        appendLedger(domain, ownerId, entryType, amountCents,
                new BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark);
    }

    // ==== 扣款 ====

    /** 非幂等扣款（可用余额校验）。 */
    @Transactional
    public void debit(WalletDomain domain, String ownerId, long amountCents, String entryType,
                      String refType, String refId, String remark) {
        runWithWalletLock(domain, ownerId, () -> {
            doDebit(domain, ownerId, amountCents, entryType, refType, refId, remark);
            return null;
        });
    }

    /** 幂等扣款（可用余额校验）。@return true=本次新扣款。 */
    @Transactional
    public boolean debitIfAbsent(WalletDomain domain, String ownerId, long amountCents, String entryType,
                                 String refType, String refId, String remark) {
        return runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(domain.column(), ownerId, refType, refId).isPresent()) {
                return false;
            }
            doDebit(domain, ownerId, amountCents, entryType, refType, refId, remark);
            return true;
        });
    }

    /**
     * 幂等扣款且<b>允许扣成负数</b>（CB-040 佣金随退款冲正、押金收取负债挂负）：无余额校验，
     * refType+refId 查重防重复扣。@return true=本次新扣款。
     */
    @Transactional
    public boolean debitIfAbsentAllowNegative(WalletDomain domain, String ownerId, long amountCents,
                                              String entryType, String refType, String refId, String remark) {
        return runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(domain.column(), ownerId, refType, refId).isPresent()) {
                return false;
            }
            WalletAccount account = reload(domain, ownerId);
            long balance = value(account.getBalanceCents()) - amountCents;
            persistBalances(domain, ownerId, balance, value(account.getFrozenCents()));
            appendLedger(domain, ownerId, entryType, -amountCents,
                    new BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark);
            return true;
        });
    }

    private void doDebit(WalletDomain domain, String ownerId, long amountCents, String entryType,
                         String refType, String refId, String remark) {
        requirePositive(amountCents);
        WalletAccount account = reload(domain, ownerId);
        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());
        if (available < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");
        }
        long balance = value(account.getBalanceCents()) - amountCents;
        persistBalances(domain, ownerId, balance, value(account.getFrozenCents()));
        appendLedger(domain, ownerId, entryType, -amountCents,
                new BalanceSnapshot(balance, value(account.getFrozenCents())), refType, refId, remark);
    }

    /**
     * 标记线下打款出账（补货员提现）：按<b>余额</b>（非可用额，无冻结）校验，不足即失败——
     * 对齐「没存钱就会提现失败」口径。幂等 refType+refId 查重。@return true=本次新出账。
     */
    @Transactional
    public boolean payoutIfAbsent(WalletDomain domain, String ownerId, long amountCents, String entryType,
                                  String refType, String refId, String remark) {
        return runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            if (refType != null && refId != null
                    && ledgerMapper.findByRef(domain.column(), ownerId, refType, refId).isPresent()) {
                return false;
            }
            WalletAccount account = reload(domain, ownerId);
            if (value(account.getBalanceCents()) < amountCents) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "余额不足，提现失败");
            }
            long balance = value(account.getBalanceCents()) - amountCents;
            long frozen = value(account.getFrozenCents());
            persistBalances(domain, ownerId, balance, frozen);
            appendLedger(domain, ownerId, entryType, -amountCents,
                    new BalanceSnapshot(balance, frozen), refType, refId, remark);
            return true;
        });
    }

    /** 幂等冲正入账：仅当原始 ref 流水存在、且冲正 ref 未记过时，按可用余额扣回。 */
    @Transactional
    public boolean reverseCreditIfPresent(WalletDomain domain, String ownerId, long amountCents, String entryType,
                                          String reverseRefType, String reverseRefId,
                                          String originalRefType, String originalRefId, String remark) {
        return runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            if (originalRefType == null || originalRefId == null
                    || ledgerMapper.findByRef(domain.column(), ownerId, originalRefType, originalRefId).isEmpty()) {
                return false;
            }
            if (reverseRefType != null && reverseRefId != null
                    && ledgerMapper.findByRef(domain.column(), ownerId, reverseRefType, reverseRefId).isPresent()) {
                return false;
            }
            doDebit(domain, ownerId, amountCents, entryType, reverseRefType, reverseRefId, remark);
            return true;
        });
    }

    /**
     * AU-181（2026-10-18 批次 5）：与 {@link #reverseCreditIfPresent} 同构的「允许扣成负数」冲正——
     * 全额退款 void 链的分账冲正（doVoidSplitOnFullRefund → reverseWalletCredit）此前走
     * {@code doDebit} 的 412 余额闸 ⇒ 余额已提空/为负的商户退款 finalize 永久回滚、split 永不
     * VOIDED（T4 当年只给了 clawbackWalletDelta 旁路，本条是被漏改的同族路径）。
     * 冲正语义保留：原 ref 流水不存在不冲、冲正 ref 已记不冲（幂等不变）。
     */
    @Transactional
    public boolean reverseCreditAllowNegativeIfPresent(WalletDomain domain, String ownerId, long amountCents,
                                                       String entryType, String reverseRefType,
                                                       String reverseRefId, String originalRefType,
                                                       String originalRefId, String remark) {
        return runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            if (originalRefType == null || originalRefId == null
                    || ledgerMapper.findByRef(domain.column(), ownerId, originalRefType, originalRefId).isEmpty()) {
                return false;
            }
            if (reverseRefType != null && reverseRefId != null
                    && ledgerMapper.findByRef(domain.column(), ownerId, reverseRefType, reverseRefId).isPresent()) {
                return false;
            }
            WalletAccount account = reload(domain, ownerId);
            long balance = value(account.getBalanceCents()) - amountCents;
            persistBalances(domain, ownerId, balance, value(account.getFrozenCents()));
            appendLedger(domain, ownerId, entryType, -amountCents,
                    new BalanceSnapshot(balance, value(account.getFrozenCents())), reverseRefType, reverseRefId, remark);
            return true;
        });
    }

    /** 某业务动作实际扣减金额（分，正数），无流水返回 0（展示口径 F-5）。 */
    @Transactional(readOnly = true)
    public long debitedCentsByRef(WalletDomain domain, String ownerId, String refType, String refId) {
        if (ownerId == null || refType == null || refId == null) {
            return 0L;
        }
        return ledgerMapper.findByRef(domain.column(), ownerId, refType, refId)
                .map(ledger -> {
                    long amount = value(ledger.getAmountCents());
                    return amount < 0L ? -amount : 0L;
                })
                .orElse(0L);
    }

    // ==== 提现冻结生命周期（仅商户/线长调用）====

    @Transactional
    public void freezeForWithdraw(WalletDomain domain, String ownerId, long amountCents,
                                  String refType, String refId, String remark) {
        runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            WalletAccount account = reload(domain, ownerId);
            long available = value(account.getBalanceCents()) - value(account.getFrozenCents());
            if (available < amountCents) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");
            }
            long frozen = value(account.getFrozenCents()) + amountCents;
            persistBalances(domain, ownerId, value(account.getBalanceCents()), frozen);
            appendLedger(domain, ownerId, "WITHDRAW_FREEZE", -amountCents,
                    new BalanceSnapshot(value(account.getBalanceCents()), frozen), refType, refId, remark);
            return null;
        });
    }

    /**
     * 释放提现冻结。<b>同一 ref 幂等</b>，且只释放本单仍占用的部分。
     *
     * <p>MA-262（R23Q-01，P1）：原实现是「账户冻结合计 ≥ 金额就减一次」，不带任何 ref 判据 ——
     * 打款失败（{@code finalizePayout} 落 FAILED）已经释放过一次，运营再点「打款失败取消」
     * （{@code cancelFailed}）又释放一次：本单冻结被清空后，第二次减掉的是<b>别的在途单</b>的
     * 冻结额；那张单后续打款成功走 {@code consumeFrozenSplit} 时冻结已不足 ⇒ 事务回滚，
     * 而渠道钱已经出账（「钱出账、余额未扣」）。单据状态挡不住这类重复（FAILED 不区分是否已释放）。
     *
     * <p>判据改为流水自证：{@code held = -(Σ WITHDRAW_FREEZE/RELEASE/PAID/FEE 金额)}，
     * {@code held<=0} ⇒ 已释放或已消费，直接空转（记 info，不再动账户）；{@code 0<held<金额}
     * ⇒ 只释放 {@code held}（历史脏数据下不stranded、也不越界吃别单）。
     * 无任何相关流水（{@code held} 不可判）时退回旧口径并留 warn —— 老单据/迁移前数据没有
     * 冻结流水，此时按账户级判定是唯一可行口径。
     *
     * <p>🔴 MA-313：{@code held} 必须在**拿到账户行锁之后**才读。这把 Redis 锁是在本方法内取、
     * {@code finally} 里放，而事务提交发生在方法返回**之后** ⇒ 解锁早于提交；并发同 ref 的两个请求
     * 都先读流水时，第二个读到的是第一个尚未提交的旧值（它的 RELEASE 不可见），夹取形同失效，
     * 仍会把别的在途单的冻结释放掉——正是本方法要修的 MA-262 形态在并发下复活。
     * 提现冻结流水按 V388 被 {@code uk_wallet_ledger_ref} 显式排除（一 ref 多行），DB 唯一键**不兜**这一类，
     * 所以顺序本身就是判据，不得调换。
     */
    @Transactional
    public void releaseFrozen(WalletDomain domain, String ownerId, long amountCents,
                              String refType, String refId, String remark) {
        runWithWalletLock(domain, ownerId, () -> {
            requirePositive(amountCents);
            WalletAccount account = reload(domain, ownerId);
            Long held = heldFrozenCents(domain, ownerId, refType, refId);
            if (held != null && held <= 0L) {
                log.info("release skipped (freeze already released/consumed) domain={} owner={} ref={}/{} amount={}",
                        domain, ownerId, refType, refId, amountCents);
                return null;
            }
            long release = held == null ? amountCents : Math.min(held, amountCents);
            if (release < amountCents) {
                log.warn("release clamped to held freeze domain={} owner={} ref={}/{} request={} held={}",
                        domain, ownerId, refType, refId, amountCents, held);
            }
            if (value(account.getFrozenCents()) < release) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结金额不足");
            }
            long frozen = value(account.getFrozenCents()) - release;
            persistBalances(domain, ownerId, value(account.getBalanceCents()), frozen);
            appendLedger(domain, ownerId, "WITHDRAW_RELEASE", release,
                    new BalanceSnapshot(value(account.getBalanceCents()), frozen), refType, refId, remark);
            return null;
        });
    }

    @Transactional
    public void consumeFrozen(WalletDomain domain, String ownerId, long amountCents,
                              String refType, String refId, String remark) {
        consumeFrozenSplit(domain, ownerId, amountCents, 0L, refType, refId, remark);
    }

    /**
     * V308 拆分记账：余额与冻结合计只扣一次 {@code net+fee}（与扣毛额等价），流水拆
     * {@code WITHDRAW_PAID -net} + {@code WITHDRAW_FEE -fee}，两行相加==毛额。
     * {@code fee=0} 时只记一行（行为等同拆分前）。
     */
    @Transactional
    public void consumeFrozenSplit(WalletDomain domain, String ownerId, long netCents, long feeCents,
                                   String refType, String refId, String remark) {
        runWithWalletLock(domain, ownerId, () -> {
            long net = Math.max(0L, netCents);
            long fee = Math.max(0L, feeCents);
            long gross = net + fee;
            if (gross < 0L) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提现金额不合法");
            }
            requirePositive(gross);
            // MA-262 另一半：账户冻结合计够 ≠ **本单**冻结够。本单冻结被别处释放/被吃后，
            // 只判合计会扣掉别的在途单的冻结额 ⇒ 那张单打款成功时回滚（钱已出账、余额未扣）。
            // 有冻结流水时按 ref 判：本单占用不足即拒付，把资损挡在渠道调用之前。
            // 🔴 MA-313：与 releaseFrozen 同纪律——行锁先于 held 读，否则并发下读到的是别人未提交的旧值。
            WalletAccount account = reload(domain, ownerId);
            Long held = heldFrozenCents(domain, ownerId, refType, refId);
            if (held != null && held < gross) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "本单冻结不足：可扣 " + held + " 分 < 应付 " + gross + " 分");
            }
            if (value(account.getBalanceCents()) < gross || value(account.getFrozenCents()) < gross) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "冻结或余额不足");
            }
            long balance = value(account.getBalanceCents()) - gross;
            long frozen = value(account.getFrozenCents()) - gross;
            persistBalances(domain, ownerId, balance, frozen);
            if (net > 0L) {
                appendLedger(domain, ownerId, "WITHDRAW_PAID", -net,
                        new BalanceSnapshot(balance + fee, frozen + fee), refType, refId, remark);
            }
            if (fee > 0L) {
                appendLedger(domain, ownerId, "WITHDRAW_FEE", -fee,
                        new BalanceSnapshot(balance, frozen), refType, refId, remark);
            }
            return null;
        });
    }

    // ==== 内部原语 ====

    private WalletAccount reload(WalletDomain domain, String ownerId) {
        doEnsureAccount(domain, ownerId);
        return accountMapper.findByOwnerForUpdate(domain.column(), ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "钱包不存在"));
    }

    private void persistBalances(WalletDomain domain, String ownerId, long balanceCents, long frozenCents) {
        accountMapper.updateBalances(domain.column(), ownerId, balanceCents, frozenCents, Instant.now());
    }

    /**
     * 该 ref 在冻结账上**仍被占用**的金额（分）；{@code null} = 无法判定（无 ref 或缺冻结流水）。
     *
     * <p>用流水自证而不是单据状态：提现单据的 FAILED 不区分「冻结已释放」（打款失败即释放）
     * 与「冻结仍在」（重试打款重新冻结后又失败），靠状态判重必然漏。冻结账是唯一事实源。
     * 无任何相关流水时返回 null 让调用方退回账户级旧口径（老单据/迁移前数据没有冻结流水）。
     *
     * <p>🔴 三类流水必须**分别**算，不能合并成一个 {@code Σamount}：{@code amount_cents} 记的是
     * **余额**方向（负=出账），而冻结方向不同——FREEZE 负记但冻结 +、RELEASE 正记且冻结 -、
     * PAID/FEE 负记且冻结 -。真库实例（ref=1：FREEZE -1000 / PAID -994 / FEE -6，frozen_after
     * 最终 0）若按 {@code -Σamount} 会得出「仍占用 2000」，恰恰是「多释放吃别人冻结」的方向。
     */
    private Long heldFrozenCents(WalletDomain domain, String ownerId, String refType, String refId) {
        if (refType == null || refId == null) {
            return null;
        }
        List<WalletLedger> rows = ledgerMapper.listByRefAndEntryTypes(
                domain.column(), ownerId, refType, refId, FROZEN_ENTRY_TYPES);
        if (rows.isEmpty()) {
            return null;
        }
        long frozen = 0L;
        long released = 0L;
        long consumed = 0L;
        for (WalletLedger row : rows) {
            long amount = row.getAmountCents() == null ? 0L : Math.abs(row.getAmountCents());
            String type = row.getEntryType();
            if ("WITHDRAW_FREEZE".equals(type)) {
                frozen += amount;
            } else if ("WITHDRAW_RELEASE".equals(type)) {
                released += amount;
            } else {
                // WITHDRAW_PAID / WITHDRAW_FEE：渠道已付，冻结同样被消耗
                consumed += amount;
            }
        }
        return Math.max(0L, frozen - released - consumed);
    }

    private void appendLedger(WalletDomain domain, String ownerId, String entryType, long amountCents,
                              BalanceSnapshot snapshot, String refType, String refId, String remark) {
        WalletLedger ledger = new WalletLedger();
        ledger.setDomain(domain.column());
        ledger.setOwnerId(ownerId);
        ledger.setEntryType(trim(entryType, 32));
        ledger.setAmountCents(amountCents);
        ledger.setBalanceAfter(snapshot.balanceAfter());
        ledger.setFrozenAfter(snapshot.frozenAfter());
        ledger.setOwnerSnapshotName(resolveSnapshotName(domain, ownerId));
        ledger.setRefType(trim(refType, 32));
        ledger.setRefId(trim(refId, 64));
        ledger.setRemark(trim(remark, 255));
        ledger.setCreatedAt(Instant.now());
        ledgerMapper.insert(ledger);
    }

    /** 仅商户域承接旧 merchant_name 快照（V241），线长/补货员为 NULL。 */
    private String resolveSnapshotName(WalletDomain domain, String ownerId) {
        if (domain != WalletDomain.MERCHANT) {
            return null;
        }
        Merchant merchant = merchantMapper.selectById(ownerId);
        return merchant == null ? null : merchant.getMerchantName();
    }

    private <T> T runWithWalletLock(WalletDomain domain, String ownerId, Supplier<T> action) {
        String key = domain.lockKey(ownerId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // 资金路径的原始异常报文（MyBatis 会把 mapper 名/SQL/SQLSTATE 写进 message）不得当客户端可见 reason，
            // 详情只进日志；状态码仍是 500，话术由 GlobalExceptionHandler 统一给追踪号。
            log.error("wallet action failed domain={} owner={}", domain, ownerId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ApiMessages.INTERNAL_ERROR, e);
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private record BalanceSnapshot(long balanceAfter, long frozenAfter) {}

    private static void requirePositive(long amountCents) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额必须大于 0");
        }
    }

    private static long value(Long v) {
        return v == null ? 0L : v;
    }

    private static String trim(String v, int maxLen) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }
}
