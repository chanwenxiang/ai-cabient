package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 商户钱包欠款核销（CB-086 第 4.1 条：2026-11-13 用户拍板「平台兜底 + 线下追缴」）。
 *
 * <p><b>为什么需要它</b>：负余额只挂在 {@code wallet_account.balance_cents} 上时，既不是应收、
 * 也不进损失科目 ——「平台兜底」不会真的发生。核销是把这个数**显式转成平台损失**的唯一动作，
 * 且必须带人、带因、带金额，才能在报表里回答「兜了多少、兜给谁」。</p>
 *
 * <p><b>门槛（缺一不可）</b>：
 * <ol>
 *   <li>余额为负（否则 409）；</li>
 *   <li>商户已非 ACTIVE（活跃商户先走「入账自动抵扣 + 线下追缴」，不许直接核销）；</li>
 *   <li>无在途提现（PENDING_REVIEW/APPROVED/PAYING）与无在途分账（ACCRUED/WECHAT_SUBMITTED/WECHAT_FAILED）
 *       —— 钱可能还会动，此时核销会把「还要收的钱」也抹掉；</li>
 *   <li>核销金额 = 执行时的实际负余额（服务端现算，不接受前端金额），幂等键由
 *       {@code merchantId + 余额} 派生 ⇒ 连点/重放不会多核第二笔；</li>
 *   <li>写入 {@code ARREARS_WRITEOFF} 流水 + 审计；金额达到调账复核阈值时同批发起
 *       {@code MERCHANT_WALLET_ADJUST} 审批（复用现有审批定义，不新增 bizType）。</li>
 * </ol>
 *
 * <p>🔴 不退余额、不动冻结、不碰分账：只让 balance 归零并留下损失痕迹。</p>
 */
@Service
public class MerchantArrearsWriteOffService {

    /** 审计动作码 + 流水类型（同一字面量，便于按类型对账）。 */
    static final String ENTRY_TYPE = "ARREARS_WRITEOFF";

    static final String REF_TYPE = "ARREARS_WRITEOFF";

    /** 复用调账审批定义（不新增 bizType，避免审批定义缺失时静默不建实例）。 */
    static final String APPROVAL_BIZ_TYPE = "MERCHANT_WALLET_ADJUST";

    /** 在途提现：钱可能还会出/被驳回解冻。 */
    static final Set<String> INFLIGHT_WITHDRAW_STATUSES = Set.of("PENDING_REVIEW", "APPROVED", "PAYING");

    /**
     * 在途分账：钱还没最终落到钱包，或失败待重试（与 {@code MerchantFinanceService}
     * 的「待分账」口径同源，另含失败可重试态）。{@code LEDGER_ONLY}/{@code SETTLED} 已入钱包，不算在途。
     */
    static final Set<String> INFLIGHT_SPLIT_STATUSES =
            Set.of("PENDING", "ACCRUED", "WECHAT_SUBMITTED", "SUBMITTED", "WECHAT_FAILED", "FAILED");

    private static final Logger log = LoggerFactory.getLogger(MerchantArrearsWriteOffService.class);

    private final MerchantMapper merchantMapper;
    private final MerchantWalletService merchantWalletService;
    private final MerchantWalletArrearsService arrearsService;
    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final OrderRevenueSplitMapper splitMapper;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;
    private final AdminAuditService auditService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final WithdrawPolicyResolver policy;
    private final DistributedLockService distributedLockService;

    public MerchantArrearsWriteOffService(MerchantMapper merchantMapper,
                                          MerchantWalletService merchantWalletService,
                                          MerchantWalletArrearsService arrearsService,
                                          MerchantWithdrawRequestMapper withdrawMapper,
                                          OrderRevenueSplitMapper splitMapper,
                                          PermissionService permissionService,
                                          MerchantScopeService merchantScopeService,
                                          AdminAuditService auditService,
                                          ApprovalWorkflowService approvalWorkflowService,
                                          WithdrawPolicyResolver policy,
                                          DistributedLockService distributedLockService) {
        this.merchantMapper = merchantMapper;
        this.merchantWalletService = merchantWalletService;
        this.arrearsService = arrearsService;
        this.withdrawMapper = withdrawMapper;
        this.splitMapper = splitMapper;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
        this.auditService = auditService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.policy = policy;
        this.distributedLockService = distributedLockService;
    }

    /**
     * 把某商户的钱包欠款清零，并把该金额记为平台损失。
     *
     * @return 核销金额（分，正数）；余额已非负时抛 409
     */
    @Transactional
    public WriteOffResult writeOff(Long operatorId, String merchantId, String reason) {
        permissionService.requirePermission(operatorId, "ops:merchant-withdraw:adjust");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        Merchant merchant = merchantMapper.findById(merchantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在"));
        if ("ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仅已停用的商户可核销欠款；活跃商户请走「入账自动抵扣 + 线下追缴」");
        }
        String lockKey = MerchantWithdrawService.merchantWalletLockKey(merchantId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商户钱包处理中，请稍后重试");
        }
        // 锁必须覆盖到事务提交之后：在事务内 finally 解锁会在「已解锁、未提交」窗口放进第二个核销。
        // 有活跃事务 ⇒ 挂 afterCompletion（提交/回滚后执行）；无事务直调（单测）⇒ 立即释放。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            distributedLockService.unlock(lockKey);
                        }
                    });
            return doWriteOff(operatorId, merchantId, merchant, reason);
        }
        try {
            return doWriteOff(operatorId, merchantId, merchant, reason);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private WriteOffResult doWriteOff(Long operatorId, String merchantId, Merchant merchant, String reason) {
        requireNoInflight(merchantId);
        MerchantWalletAccount account = merchantWalletService.findAccount(merchantId);
        long balance = account == null || account.getBalanceCents() == null ? 0L : account.getBalanceCents();
        long frozen = account == null || account.getFrozenCents() == null ? 0L : account.getFrozenCents();
        if (balance >= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该商户当前无欠款可核销");
        }
        if (frozen > 0) {
            // 冻结额度通常对应在途提现/预扣；此时核销可能把「还要付出去的钱」也抹掉
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "存在冻结资金，可能还有在途提现/预扣，请先处理冻结后再核销");
        }
        long amountCents = -balance;
        // 幂等键带「欠款段」：同一段（起算日相同）连点/重放只记一笔；结清后再次欠款是新段，
        // 必须允许再次核销——只按金额派生会让第二段被旧键静默吞掉（不记账、不审计）。
        String episode = arrearsService.arrearsOf(merchantId)
                .map(MerchantWalletArrearsService.Arrears::since)
                .map(java.time.LocalDate::toString)
                .orElseGet(() -> java.time.LocalDate.now(com.aicabinet.trade.support.ScheduleZones.ZONE).toString());
        String refId = "WO:" + merchantId + ":" + amountCents + ":" + episode;
        String note = "商户欠款核销：" + (reason == null || reason.isBlank() ? "平台兜底" : reason.trim());
        boolean written = merchantWalletService.creditIfAbsent(
                merchantId, amountCents, ENTRY_TYPE, REF_TYPE, refId, note);
        if (!written) {
            // 幂等命中：同一欠款段、同一金额的重放，不再重复记审计
            log.info("arrears write-off already recorded merchant={} amount={} episode={}",
                    merchantId, amountCents, episode);
            return new WriteOffResult(merchantId, amountCents, true);
        }
        auditService.appendLog(operatorId, ENTRY_TYPE, "MERCHANT_WALLET", merchantId,
                "核销金额(分)=" + amountCents + "；欠款段=" + episode + "；原因=" + note);
        if (amountCents >= policy.merchantReviewThresholdCents()) {
            approvalWorkflowService.start(APPROVAL_BIZ_TYPE, refId, operatorId,
                    "商户欠款核销 " + merchant.getMerchantName() + " ¥"
                            + BigDecimal.valueOf(amountCents, 2).toPlainString());
        }
        log.warn("merchant wallet arrears written off (platform absorbs): merchant={} amount={} operator={} episode={}",
                merchantId, amountCents, operatorId, episode);
        return new WriteOffResult(merchantId, amountCents, false);
    }

    private void requireNoInflight(String merchantId) {
        Long withdraws = withdrawMapper.selectCount(Wrappers.<MerchantWithdrawRequest>lambdaQuery()
                .eq(MerchantWithdrawRequest::getMerchantId, merchantId)
                .in(MerchantWithdrawRequest::getStatus, INFLIGHT_WITHDRAW_STATUSES));
        if (withdraws != null && withdraws > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存在在途提现，核销会抹掉待处理金额，请先结清提现");
        }
        Long splits = splitMapper.selectCount(Wrappers.<OrderRevenueSplit>lambdaQuery()
                .eq(OrderRevenueSplit::getMerchantId, merchantId)
                .in(OrderRevenueSplit::getStatus, INFLIGHT_SPLIT_STATUSES));
        if (splits != null && splits > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存在在途分账，钱可能还会入账，请待分账终态后再核销");
        }
    }

    /** @param alreadyRecorded true=幂等命中（同金额核销流水已存在），本次未新增损失 */
    public record WriteOffResult(String merchantId, long amountCents, boolean alreadyRecorded) {}
}
