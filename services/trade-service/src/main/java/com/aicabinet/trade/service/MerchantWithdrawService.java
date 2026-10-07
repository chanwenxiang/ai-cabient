package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWalletAccountDto;
import com.aicabinet.common.dto.MerchantWalletLedgerDto;
import com.aicabinet.common.dto.MerchantWalletOverviewDto;
import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWalletLedger;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelLimits;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.util.BizIds;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class MerchantWithdrawService {
    private static final Logger log = LoggerFactory.getLogger(MerchantWithdrawService.class);

    private static final String PERM_OPS_MERCHANT_WITHDRAW_REVIEW = "ops:merchant-withdraw:review";
    private static final String PERM_OPS_MERCHANT_WITHDRAW_LIST = "ops:merchant-withdraw:list";
    private static final String MERCHANT_WITHDRAW_REVIEW = "MERCHANT_WITHDRAW_REVIEW";
    private static final String PERM_OPS_FINANCE_VIEW = "ops:finance:view";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String WITHDRAW = "WITHDRAW";


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantWalletAccountMapper accountMapper;
    private final MerchantWalletLedgerMapper ledgerMapper;
    private final MerchantWalletService merchantWalletService;
    private final MerchantWithdrawPayoutService payoutService;
    private final MerchantWithdrawProperties properties;
    private final PayoutAccountService payoutAccountService;
    private final PayoutAccountMapper payoutAccountMapper;
    /** V307：限额/费率解析（运营台配置优先于 yml，运行期生效）。 */
    private final WithdrawPolicyResolver policy;
    /** V308：渠道硬限额（单笔 / 单收款人单日 / 单通道当日总额）。 */
    private final PayoutChannelRegistry payoutChannelRegistry;
    /** V308：T+1 可提现闸门（已入钱包但未到可提现日的金额）。 */
    private final OrderRevenueSplitMapper orderRevenueSplitMapper;
    /** V321 提现资质门禁（实名 + 商户主体资质）。 */
    private final WithdrawEligibilityService withdrawEligibilityService;
    private final MerchantFeaturePackService merchantFeaturePackService;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final MerchantWithdrawService self;

    private static final String BIZ_MERCHANT_WITHDRAW = "MERCHANT_WITHDRAW";
    private static final String BIZ_WALLET_ADJUST = "MERCHANT_WALLET_ADJUST";
    public MerchantWithdrawService(MerchantWithdrawRequestMapper withdrawMapper,
                                   MerchantMapper merchantMapper,
                                   MerchantWalletAccountMapper accountMapper,
                                   MerchantWalletLedgerMapper ledgerMapper,
                                   MerchantWalletService merchantWalletService,
                                   MerchantWithdrawPayoutService payoutService,
                                   MerchantWithdrawProperties properties,
                                   PayoutAccountService payoutAccountService,
                                   PayoutAccountMapper payoutAccountMapper,
                                   MerchantFeaturePackService merchantFeaturePackService,
                                   MerchantScopeService merchantScopeService,
                                   PermissionService permissionService,
                                   AdminAuditService auditService,
                                   DistributedLockService distributedLockService,
                                   ApprovalWorkflowService approvalWorkflowService,
                                   WithdrawPolicyResolver policy,
                                   PayoutChannelRegistry payoutChannelRegistry,
                                   OrderRevenueSplitMapper orderRevenueSplitMapper,
                                   WithdrawEligibilityService withdrawEligibilityService,
                                   @Lazy MerchantWithdrawService self) {
        this.withdrawMapper = withdrawMapper;
        this.merchantMapper = merchantMapper;
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.merchantWalletService = merchantWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
        this.payoutAccountService = payoutAccountService;
        this.payoutAccountMapper = payoutAccountMapper;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.policy = policy;
        this.payoutChannelRegistry = payoutChannelRegistry;
        this.orderRevenueSplitMapper = orderRevenueSplitMapper;
        this.withdrawEligibilityService = withdrawEligibilityService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantWalletAccountDto> listAccounts(Long operatorId, String keyword, int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                PERM_OPS_MERCHANT_WITHDRAW_LIST, PERM_OPS_FINANCE_VIEW, "ops:merchant:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        if (allowed.isEmpty()) {
            return new PageResult<>(List.of(), p, s, 0);
        }
        LambdaQueryWrapper<Merchant> q = new LambdaQueryWrapper<>();
        q.in(Merchant::getMerchantId, allowed);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(Merchant::getMerchantId, kw)
                    .or().like(Merchant::getMerchantName, kw)
                    .or().like(Merchant::getContactPhone, kw));
        }
        q.orderByAsc(Merchant::getMerchantId);
        Page<Merchant> result = merchantMapper.selectPage(new Page<>(p + 1L, s), q);
        List<MerchantWalletAccountDto> items = result.getRecords().stream().map(this::toAccountDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public List<MerchantWalletLedgerDto> ledgers(Long operatorId, String merchantId, int limit) {
        permissionService.requireAnyPermission(operatorId,
                PERM_OPS_MERCHANT_WITHDRAW_LIST, PERM_OPS_FINANCE_VIEW, "ops:merchant:list");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        requireMerchant(merchantId);
        return ledgerMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, limit).stream()
                .map(this::toLedgerDto)
                .toList();
    }

    @Transactional
    public MerchantWalletAccountDto adjust(Long operatorId, String merchantId, long amountCents, String remark) {
        permissionService.requirePermission(operatorId, "ops:merchant-withdraw:adjust");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        requireMerchant(merchantId);
        if (amountCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调账金额不能为 0");
        }
        return runWithMerchantWalletLock(merchantId, () -> {
            String refId = BizIds.nextNumeric();
            String note = remark == null || remark.isBlank() ? "运营调账" : remark.trim();
            if (amountCents > 0) {
                merchantWalletService.credit(merchantId, amountCents, "ADJUST", "OPS_ADJUST", refId, note);
            } else {
                merchantWalletService.debit(merchantId, -amountCents, "ADJUST", "OPS_ADJUST", refId, note);
            }
            auditService.appendLog(operatorId, BIZ_WALLET_ADJUST, "MERCHANT_WALLET", merchantId,
                    "金额(分)=" + amountCents + "；备注=" + note);
            if (Math.abs(amountCents) >= policy.merchantReviewThresholdCents()) {
                Merchant merchant = requireMerchant(merchantId);
                approvalWorkflowService.start(
                        BIZ_WALLET_ADJUST,
                        refId,
                        operatorId,
                        "商户调账 " + merchant.getMerchantName() + " ¥"
                                + String.format(Locale.ROOT, "%.2f", amountCents / 100.0));
            }
            return toAccountDto(requireMerchant(merchantId));
        });
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantWithdrawRequestDto> listWithdraws(
            Long operatorId, String status, String merchantId, int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                PERM_OPS_MERCHANT_WITHDRAW_LIST, PERM_OPS_MERCHANT_WITHDRAW_REVIEW, PERM_OPS_FINANCE_VIEW);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        if (allowed.isEmpty()) {
            return new PageResult<>(List.of(), p, s, 0);
        }
        LambdaQueryWrapper<MerchantWithdrawRequest> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            q.eq(MerchantWithdrawRequest::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (merchantId != null && !merchantId.isBlank()) {
            String mid = merchantId.trim();
            merchantScopeService.requireMerchantAccess(operatorId, mid);
            q.eq(MerchantWithdrawRequest::getMerchantId, mid);
        } else {
            q.in(MerchantWithdrawRequest::getMerchantId, allowed);
        }
        q.orderByDesc(MerchantWithdrawRequest::getCreatedAt);
        Page<MerchantWithdrawRequest> result = withdrawMapper.selectPage(new Page<>(p + 1L, s), q);
        List<MerchantWithdrawRequestDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> payoutMode(Long operatorId) {
        permissionService.requireAnyPermission(operatorId,
                PERM_OPS_MERCHANT_WITHDRAW_LIST, PERM_OPS_MERCHANT_WITHDRAW_REVIEW, PERM_OPS_FINANCE_VIEW);
        return payoutService.modeInfo();
    }

    /** 无外层长事务：申请落库短事务提交后再打款，避免渠道占用 DB 连接。 */
    public MerchantWithdrawRequestDto apply(Long operatorId, String merchantId, long amountCents, String requestNo) {
        permissionService.requirePermission(operatorId, "ops:merchant-withdraw:adjust");
        merchantScopeService.requireMerchantAccess(operatorId, merchantId);
        Merchant merchant = requireMerchant(merchantId);
        // 🔴 V321 商户主体资质门禁（运营代提现场景同样要过）。
        //    ⚠️ **不查申请人实名**：这里的 submitter 是**运营人员**（operatorId），
        //    拿运营的实名状态去代表商户资质是错的 —— 商户主体资质与操作者实名是两件事。
        withdrawEligibilityService.requireMerchantWithdrawEligible(merchantId, null);
        return createWithdraw(merchant, amountCents, requestNo, operatorId);
    }

    public MerchantWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        return merchantApply(userId, amountCents, requestNo, null);
    }

    /**
     * 商户自主提现：多商户绑定时必须显式指定 merchantId（避免取字典序第一个）。
     */
    public MerchantWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo, String merchantId) {
        String resolvedMerchantId = resolveMerchantId(userId, merchantId);
        Merchant merchant = requireMerchant(resolvedMerchantId);
        // 🔴 V321 资质门禁：必须在 createWithdraw **之前** ——
        //    createWithdraw 会开钱包锁、可能直接执行打款（gate.shouldPayout）。
        //    资质不合格却先打款再报错，钱已经出去了。
        withdrawEligibilityService.requireMerchantWithdrawEligible(resolvedMerchantId, userId);
        return createWithdraw(merchant, amountCents, requestNo, userId);
    }

    @Transactional(readOnly = true)
    public MerchantWalletOverviewDto merchantOverview(Long userId) {
        return merchantOverview(userId, null);
    }

    @Transactional(readOnly = true)
    public MerchantWalletOverviewDto merchantOverview(Long userId, String merchantIdParam) {
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return new MerchantWalletOverviewDto(
                    false, null, null, null, null, null, List.of(), List.of());
        }
        String merchantId;
        if (merchantIdParam != null && !merchantIdParam.isBlank()) {
            merchantId = merchantIdParam.trim();
            if (!merchantIds.contains(merchantId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该商户钱包");
            }
        } else {
            // 未指定时保持旧行为：单商户直接用；多商户取第一个（只读视图不阻断）
            merchantId = merchantIds.stream().sorted().findFirst().orElse(null);
        }
        Merchant merchant = merchantMapper.findById(merchantId).orElse(null);
        if (merchant == null) {
            return new MerchantWalletOverviewDto(
                    false, null, null, null, null, null, List.of(), List.of());
        }
        MerchantWalletAccount account = merchantWalletService.ensureAccount(merchantId);
        long balance = value(account.getBalanceCents());
        long frozen = value(account.getFrozenCents());
        return new MerchantWalletOverviewDto(
                true,
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                balance,
                frozen,
                balance - frozen,
                ledgerMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, 10).stream()
                        .map(this::toLedgerDto)
                        .toList(),
                withdrawMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, 10).stream()
                        .map(this::toDto)
                        .toList()
        );
    }

    /** 审核短事务提交后，若终审通过再事务外打款。 */
    public MerchantWithdrawRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_WITHDRAW_REVIEW);
        MerchantWithdrawRequest request = requireRequest(requestId);
        merchantScopeService.requireMerchantAccess(operatorId, request.getMerchantId());
        return runWithMerchantWalletLock(request.getMerchantId(), () -> {
            PayoutGate gate = self.completeReview(operatorId, requestId, approve, remark);
            if (gate.shouldPayout()) {
                return self.executePayout(gate.requestId());
            }
            return gate.dto();
        });
    }

    @Transactional
    public PayoutGate completeReview(Long operatorId, long requestId, boolean approve, String remark) {
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!"PENDING_REVIEW".equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可审核");
        }
        Instant now = Instant.now();
        request.setReviewerId(operatorId);
        request.setReviewRemark(trim(remark));
        request.setReviewedAt(now);
        request.setUpdatedAt(now);
        if (!approve) {
            approvalWorkflowService.completeRejected(
                    operatorId, BIZ_MERCHANT_WITHDRAW, String.valueOf(request.getRequestId()), trim(remark));
            request.setStatus("REJECTED");
            withdrawMapper.updateById(request);
            merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现驳回释放");
            auditService.appendLog(operatorId, MERCHANT_WITHDRAW_REVIEW, BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(request.getRequestId()), "驳回；金额(分)=" + request.getAmountCents()
                            + "；备注=" + trim(remark));
            return PayoutGate.done(toDto(request));
        }
        approvalWorkflowService.completeApproved(
                operatorId, BIZ_MERCHANT_WITHDRAW, String.valueOf(request.getRequestId()), trim(remark));
        if (!approvalWorkflowService.isInstanceApproved(
                BIZ_MERCHANT_WITHDRAW, String.valueOf(request.getRequestId()))) {
            auditService.appendLog(operatorId, MERCHANT_WITHDRAW_REVIEW, BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(request.getRequestId()), "初审通过；金额(分)=" + request.getAmountCents());
            return PayoutGate.done(toDto(request));
        }
        request.setStatus(STATUS_APPROVED);
        withdrawMapper.updateById(request);
        auditService.appendLog(operatorId, MERCHANT_WITHDRAW_REVIEW, BIZ_MERCHANT_WITHDRAW,
                String.valueOf(request.getRequestId()), "通过；金额(分)=" + request.getAmountCents()
                        + "；备注=" + trim(remark));
        return PayoutGate.needPayout(toDto(request));
    }

    public MerchantWithdrawRequestDto payout(Long operatorId, long requestId) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_WITHDRAW_REVIEW);
        MerchantWithdrawRequest request = requireRequest(requestId);
        merchantScopeService.requireMerchantAccess(operatorId, request.getMerchantId());
        return runWithMerchantWalletLock(request.getMerchantId(), () -> {
            if (!Set.of(STATUS_APPROVED, "FAILED").contains(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
            }
            auditService.appendLog(operatorId, "MERCHANT_WITHDRAW_PAYOUT", BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(requestId), "打款金额(分)=" + request.getAmountCents());
            return self.executePayout(requestId);
        });
    }

    /**
     * 打款失败后取消：解冻资金并标记 CANCELLED，不可再重试打款。
     */
    @Transactional
    public MerchantWithdrawRequestDto cancelFailed(Long operatorId, long requestId, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_WITHDRAW_REVIEW);
        MerchantWithdrawRequest request = requireRequest(requestId);
        merchantScopeService.requireMerchantAccess(operatorId, request.getMerchantId());
        return runWithMerchantWalletLock(request.getMerchantId(), () -> {
            if (!"FAILED".equals(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "仅打款失败的提现单可取消解冻");
            }
            Instant now = Instant.now();
            String note = trim(remark);
            request.setStatus("CANCELLED");
            request.setReviewRemark(note == null || note.isBlank() ? "打款失败取消解冻" : note);
            request.setUpdatedAt(now);
            withdrawMapper.updateById(request);
            merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现失败取消解冻");
            auditService.appendLog(operatorId, "MERCHANT_WITHDRAW_CANCEL", BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(requestId), "取消解冻；金额(分)=" + request.getAmountCents());
            return toDto(request);
        });
    }

    private MerchantWithdrawRequestDto createWithdraw(Merchant merchant, long amountCents, String requestNo,
                                                      Long submitterUserId) {
        return runWithMerchantWalletLock(merchant.getMerchantId(), () -> {
            PayoutGate gate = self.persistWithdrawApplication(merchant, amountCents, requestNo, submitterUserId);
            if (gate.shouldPayout()) {
                return self.executePayout(gate.requestId());
            }
            return gate.dto();
        });
    }

    @Transactional
    public PayoutGate persistWithdrawApplication(Merchant merchant, long amountCents, String requestNo,
                                                 Long submitterUserId) {
        // V307：申请时锁定收款账户（不指定则用默认）—— 快照后历史不可变。
        // 🔴 必须在 validateAmount 之前：限额校验依赖账户的 channel 与 accountId
        //    （单收款人单日 / 单通道当日总额两个维度都要用到）。
        PayoutAccount payeeAccount = resolvePayeeForApply(merchant.getMerchantId());
        validateAmount(merchant.getMerchantId(), amountCents, payeeAccount);
        String no = normalizeRequestNo(requestNo);
        var existing = withdrawMapper.findByRequestNo(no);
        if (existing.isPresent()) {
            return PayoutGate.done(toDto(existing.get()));
        }
        Instant now = Instant.now();
        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestNo(no);
        request.setMerchantId(merchant.getMerchantId());
        request.setMerchantName(merchant.getMerchantName());
        request.setAmountCents(amountCents);
        request.setFeeCents(WithdrawFeeCalculator.computeFeeCents(
                amountCents, policy.merchantFeeCents(), policy.merchantFeeBps(), policy.merchantFeeCapCents()));
        // 🔴 渠道取自「所选收款账户」，不再是 mock 开关的字面量：一个商户可按账户走不同通道
        request.setPayChannel(payeeAccount.getChannel());
        applyPayeeSnapshot(request, payeeAccount);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= policy.merchantReviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
            applyIdemKey(request);
            merchantWalletService.freezeForWithdraw(merchant.getMerchantId(), amountCents,
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
            approvalWorkflowService.start(
                    BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(request.getRequestId()),
                    submitterUserId,
                    "商户提现 " + request.getRequestNo() + " ¥"
                            + String.format(Locale.ROOT, "%.2f", amountCents / 100.0));
            return PayoutGate.done(toDto(request));
        }
        request.setStatus(STATUS_APPROVED);
        request.setReviewRemark("低于审核阈值自动通过");
        request.setReviewedAt(now);
        withdrawMapper.insert(request);
        applyIdemKey(request);
        merchantWalletService.freezeForWithdraw(merchant.getMerchantId(), amountCents,
                WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
        return PayoutGate.needPayout(toDto(request));
    }

    /**
     * 写打款幂等键。<b>必须在 insert 之后</b> —— 键里含 requestId（自增主键）。
     *
     * <p>形态：{@code MW:<requestId>:<随机>}。随机段是为了让「同一 requestId 的不同重试」
     * 保持同一个键（重试不换键 ⇒ 渠道侧幂等），而不同单据天然不同。
     */
    private void applyIdemKey(MerchantWithdrawRequest request) {
        request.setIdemKey(PayoutAccountService.newIdemKey(
                MerchantWithdrawPayoutService.IDEM_PREFIX, request.getRequestId()));
        withdrawMapper.updateById(request);
    }

    /**
     * 取申请该用的收款账户。
     *
     * <p>🔴 <b>不提供「未装配 ⇒ 兜底」分支</b>：曾写过 {@code payoutAccountService == null ⇒ 抛 500}，
     * 结果把并发测试的 4 个用例全拦成 500 —— 测的是「兜底生效」而非「提现流程正确」，
     * 反而掩盖了真实问题。现在依赖由 Spring 保证装配，测试则显式注入 mock。
     */
    private PayoutAccount resolvePayeeForApply(String merchantId) {
        return payoutAccountService.resolveForApply(
                PayoutConstants.PAYEE_OWNER_MERCHANT, merchantId, null);
    }

    /**
     * 把收款账户信息<b>快照</b>进提现单。
     *
     * <p>只写掩码，<b>永不写明文</b>：快照列可能被列表接口直接返回。
     */
    private void applyPayeeSnapshot(MerchantWithdrawRequest request, PayoutAccount account) {
        request.setPayoutAccountId(account.getAccountId());
        request.setPayeeAccountType(account.getAccountType());
        request.setPayeeAccountName(account.getAccountName());
        request.setPayeeAccountNoMask(account.getAccountNoMask());
        request.setPayeeBankName(account.getBankName());
        request.setPayeeTaxNo(account.getTaxNo());
    }

    /**
     * 短事务标 PAYING → 渠道打款（事务外）→ 短事务落 PAID/FAILED。
     *
     * <p>V307：打款时按 {@code payoutAccountId} 取<b>申请时快照</b>对应的账户；
     * 若该账户已被物理删除（不应发生，仅数据损坏），回落查主体默认账户并留日志。
     */
    public MerchantWithdrawRequestDto executePayout(long requestId) {
        MerchantWithdrawRequest paying = self.markPaying(requestId);
        Merchant merchant = requireMerchant(paying.getMerchantId());
        PayoutAccount account = resolveSnapshotAccount(paying);
        MerchantWithdrawPayoutService.PayoutResult result = payoutService.payout(paying, merchant, account);
        return self.finalizePayout(requestId, result);
    }

    /**
     * 取本次提现单的收款账户快照源。
     *
     * <p>优先级：① 单据上的 payout_account_id（申请时用的那个，最准确）
     * → ② 主体默认账户（老单无 id 时的兜底）。
     *
     * <p>⚠️ <b>不按快照内容出款</b>：快照的 {@code payee_*} 列只用于展示与对账，
     * 真实出款必须解密账户密文（快照里没有明文）。
     */
    private PayoutAccount resolveSnapshotAccount(MerchantWithdrawRequest request) {
        if (request.getPayoutAccountId() != null) {
            Optional<PayoutAccount> snapshot =
                    payoutAccountMapper.findByAccountId(request.getPayoutAccountId());
            if (snapshot.isPresent()) {
                return snapshot.get();
            }
            // 🔴 走到这里说明「申请时用的账户」被物理删除了（只停用不会）。
            //    回落默认账户 + 留 warn：宁可打款到当前默认账户，也不要整笔卡在 PAYING。
            log.warn("withdraw snapshot account missing, fallback to default: requestId={}, accountId={}",
                    request.getRequestId(), request.getPayoutAccountId());
        }
        return payoutAccountService.resolveForApply(
                PayoutConstants.PAYEE_OWNER_MERCHANT, request.getMerchantId(), null);
    }

    @Transactional
    public MerchantWithdrawRequest markPaying(long requestId) {
        MerchantWithdrawRequest request = requireRequest(requestId);
        String previousStatus = request.getStatus();
        if (!Set.of(STATUS_APPROVED, "FAILED", "PAYING").contains(previousStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        request.setStatus("PAYING");
        request.setUpdatedAt(Instant.now());
        withdrawMapper.updateById(request);
        if ("FAILED".equals(previousStatus)) {
            // FAILED 落账时已释放冻结；重试打款前需重新冻结，保证 PAID consumeFrozen 口径
            merchantWalletService.freezeForWithdraw(request.getMerchantId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "重试打款重新冻结");
        }
        return request;
    }

    @Transactional
    public MerchantWithdrawRequestDto finalizePayout(long requestId,
                                                     MerchantWithdrawPayoutService.PayoutResult result) {
        MerchantWithdrawRequest request = requireRequest(requestId);
        Instant now = Instant.now();
        request.setPayChannel(result.payChannel());
        request.setPayoutRef(result.payoutRef());
        request.setPayoutMessage(trim(result.message()));
        request.setUpdatedAt(now);
        if (result.success()) {
            request.setStatus("PAID");
            request.setPaidAt(now);
            withdrawMapper.updateById(request);
            // V308：拆分记账 —— 渠道实发净额，手续费单列一行（合计仍等于毛额）
            long feeCents = request.getFeeCents() == null ? 0L : request.getFeeCents();
            merchantWalletService.consumeFrozenSplit(
                    request.getMerchantId(),
                    WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), feeCents),
                    feeCents,
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款成功");
            return toDto(request);
        }
        request.setStatus("FAILED");
        withdrawMapper.updateById(request);
        // 打款失败即释放冻结，避免冻结悬挂（与人工 cancelFailed 解冻口径一致；日限额统计仍排除终态）
        merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                WITHDRAW, String.valueOf(request.getRequestId()), "提现打款失败释放");
        return toDto(request);
    }

    /** 申请/审核落库结果：是否需要在事务提交后发起打款。 */
    public record PayoutGate(MerchantWithdrawRequestDto dto, boolean shouldPayout, long requestId) {
        static PayoutGate done(MerchantWithdrawRequestDto dto) {
            return new PayoutGate(dto, false, dto.requestId() == null ? 0L : dto.requestId());
        }

        static PayoutGate needPayout(MerchantWithdrawRequestDto dto) {
            return new PayoutGate(dto, true, dto.requestId());
        }
    }

    /** 打款卡 PAYING 的超时阈值：见 {@link WithdrawPayoutPolicy#PAYING_TIMEOUT_MINUTES}（P3-1b 单点化）。 */
    static final long PAYING_TIMEOUT_MINUTES = WithdrawPayoutPolicy.PAYING_TIMEOUT_MINUTES;

    /**
     * PAYING 超过 {@link #PAYING_TIMEOUT_MINUTES} 分钟的提现单兜底处置：
     * MOCK 渠道置 FAILED 并按 cancelFailed 同口径解冻（之后可走 payout() 重试，重试会重新冻结）；
     * 真实渠道不自动置失败（F3：回执丢失时自动失败会造成「已出款+已解冻」双重支出），转人工核对渠道单。
     *
     * @return 本次处理单数
     */
    @Transactional
    public int failStalePayingWithdraws() {
        Instant cutoff = Instant.now().minus(PAYING_TIMEOUT_MINUTES, java.time.temporal.ChronoUnit.MINUTES);
        List<MerchantWithdrawRequest> stale =
                withdrawMapper.findByStatusAndUpdatedAtBefore("PAYING", cutoff);
        int failed = 0;
        for (MerchantWithdrawRequest staleRequest : stale) {
            try {
                if (failSingleStalePaying(staleRequest.getRequestId())) {
                    failed++;
                }
            } catch (Exception e) {
                log.warn("stale PAYING merchant withdraw sweep failed requestId={} err={}",
                        staleRequest.getRequestId(), e.toString());
            }
        }
        return failed;
    }

    private boolean failSingleStalePaying(long requestId) {
        return runWithMerchantWalletLock(requireRequest(requestId).getMerchantId(), () -> {
            MerchantWithdrawRequest request = requireRequest(requestId);
            if (!"PAYING".equals(request.getStatus())) {
                return false;
            }
            // F3 判据单点化（P3-1b）：真实渠道回执丢失时自动置 FAILED = 双重支出，禁止
            if (!WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout(request.getPayChannel())) {
                log.warn("stale PAYING merchant withdraw on real channel left for manual reconciliation requestId={} channel={}",
                        requestId, request.getPayChannel());
                auditService.appendLog(0L, "MERCHANT_WITHDRAW_PAYOUT_STALE_MANUAL", BIZ_MERCHANT_WITHDRAW,
                        String.valueOf(requestId),
                        WithdrawPayoutPolicy.payingTimeoutManualNote(request.getPayChannel(), request.getAmountCents()));
                return false;
            }
            request.setStatus("FAILED");
            request.setPayoutMessage(WithdrawPayoutPolicy.payingTimeoutFailMessage(PAYING_TIMEOUT_MINUTES));
            request.setUpdatedAt(Instant.now());
            withdrawMapper.updateById(request);
            merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款超时释放");
            auditService.appendLog(0L, "MERCHANT_WITHDRAW_PAYOUT_TIMEOUT", BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(requestId),
                    WithdrawPayoutPolicy.payingTimeoutAutoNote(request.getAmountCents()));
            return true;
        });
    }

    private void validateAmount(String merchantId, long amountCents, PayoutAccount payeeAccount) {
        if (amountCents < policy.merchantMinAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "最低提现 " + (policy.merchantMinAmountCents() / 100.0) + " 元");
        }        // V307 补单笔上限：原先只控下限与单日，运营手工输入大额无代码层拦截
        if (policy.merchantMaxAmountCents() > 0 && amountCents > policy.merchantMaxAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单笔提现上限 " + (policy.merchantMaxAmountCents() / 100.0) + " 元");
        }
        MerchantWalletAccount account = merchantWalletService.ensureAccount(merchantId);
        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());
        // 🔴 V308：扣掉「已入钱包但还没到 T+1 可提现日」的钱。
        //    settleAfter 此前只是展示字段 —— creditWalletIfLedgerOnly 下单即入钱包，
        //    商户能当天提走昨天货款，T+1 风控（等退款/争议窗口）形同虚设。
        long pendingSettle = pendingSettleAmount(merchantId);
        if (available - pendingSettle < amountCents) {
            String extra = pendingSettle > 0
                    ? "（含待结算 " + yuan(pendingSettle) + " 元，需到可提现日之后）"
                    : "";
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足" + extra);
        }
        Instant start = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long used = withdrawMapper.sumAmountByMerchantSince(merchantId, start);
        if (used + amountCents > policy.merchantDailyLimitCents()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "超过单日提现限额");
        }
        validateChannelLimits(payeeAccount, amountCents, start);
    }

    /**
     * 渠道维度限额校验（V308）。
     *
     * <p>🔴 <b>为什么必须在申请时拦，而不是等渠道拒</b>：渠道硬拒时钱已冻结、状态已 PAYING，
     * 只能转人工释放冻结 —— 商户体验是「申请成功但迟迟不到账」。三个维度分别对应：
     * <ol>
     *   <li><b>单笔</b> —— 微信默认 ¥200（2026-10-06 用户纠正：200 是<b>单笔</b>，不是总额）；</li>
     *   <li><b>单收款人单日</b> —— 同一收款账户当日累计（微信 ¥2000）；</li>
     *   <li><b>单通道当日总额</b> —— 该打款通道<b>全平台</b>当日累计（微信单商户号 ¥5 万，
     *       是<b>共享池</b>，不是每商户各一份）。</li>
     * </ol>
     *
     * <p><b>限额取渠道硬限与运营限的更严者</b>（{@link PayoutChannelLimits#effective}）——
     * 运营限额可以把渠道额度调更小，但<b>永远调不松</b>（那等于让商户撞渠道拒单）。
     *
     * <p>⚠️ 三维度都基于 {@code createdAt 当日 + 非终态单}统计，与
     * {@link com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper} 内各查询口径一致；
     * 打款失败置 FAILED 会<b>释放额度</b>（商户可重新申请），与商户维度日限额口径一致。
     */
    private void validateChannelLimits(PayoutAccount payeeAccount, long amountCents, Instant dayStart) {
        if (payeeAccount == null || payeeAccount.getChannel() == null) {
            return;
        }
        PayoutChannel channel = payoutChannelRegistry.find(payeeAccount.getChannel()).orElse(null);
        if (channel == null) {
            return;
        }
        PayoutChannelLimits opsLimits = new PayoutChannelLimits(
                policy.merchantMaxAmountCents(), 0L, policy.merchantDailyLimitCents());
        PayoutChannelLimits limits = channel.channelLimits().effective(opsLimits);

        if (limits.hasSingleLimit() && amountCents > limits.singleCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    channel.channel() + " 单笔提现上限 " + yuan(limits.singleCents())
                            + " 元（渠道额度限制，超出请分次提现或改用银行通道）");
        }
        if (limits.hasPerPayeeDailyLimit() && payeeAccount.getAccountId() != null) {
            long payeeUsed = withdrawMapper.sumAmountByPayeeSince(payeeAccount.getAccountId(), dayStart);
            if (payeeUsed + amountCents > limits.perPayeeDailyCents()) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "该收款账户今日已提现 " + yuan(payeeUsed) + " 元，"
                                + channel.channel() + " 单收款人单日上限 " + yuan(limits.perPayeeDailyCents())
                                + " 元（超出请明日再试或改用银行通道）");
            }
        }
        if (limits.hasDailyTotalLimit()) {
            long channelUsed = withdrawMapper.sumAmountByChannelSince(payeeAccount.getChannel(), dayStart);
            if (channelUsed + amountCents > limits.dailyTotalCents()) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        channel.channel() + " 今日额度已用尽（已用 " + yuan(channelUsed) + " 元 / 上限 "
                                + yuan(limits.dailyTotalCents()) + " 元），请明日再试或改用银行通道");
            }
        }
    }

    private static String yuan(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }

    /**
     * 已入钱包但尚未到 T+1 可提现日的金额（分）。
     *
     * <p><b>为何做成「查询式扣减」而不是「入账时冻结」</b>：改入账路径要动分账主链路
     * （含幂等、冲正、部分退款重算），风险面远大于收益；而且商户钱包的 {@code frozen_cents}
     * 已被提现流程独占占用，再塞一种语义会让「提现冻结」与「待结算冻结」互相污染，
     * 出问题时无法区分谁该解冻。
     *
     * <p><b>代价</b>：每次提现多一次 {@code order_revenue_split} 聚合查询。
     * 提现是人工触发的低频操作，这个代价可接受（与 {@link WithdrawPolicyResolver}
     * 每次读 SystemConfig 同理——都不缓存，避免「刚改的限额不生效」）。
     *
     * <p>⚠️ 依赖注入缺失时返回 0（=不拦截）而非抛错：这是<b>收紧</b>型闸门，
     * 取 0 意味着回到接入前行为；反过来 fail-closed 会在装配异常时让所有提现全挂。
     */
    private long pendingSettleAmount(String merchantId) {
        if (orderRevenueSplitMapper == null) {
            return 0L;
        }
        return orderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable(merchantId, LocalDate.now(ZONE));
    }

    private String resolveMerchantId(Long userId, String merchantIdParam) {
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "运营账号请走后台调账/代提现");
        }
        if (merchantIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未绑定商户");
        }
        if (merchantIdParam != null && !merchantIdParam.isBlank()) {
            String mid = merchantIdParam.trim();
            if (!merchantIds.contains(mid)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "提现商户与账号绑定不一致");
            }
            return mid;
        }
        if (merchantIds.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定提现商户");
        }
        return merchantIds.stream().sorted(Comparator.naturalOrder()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "未绑定商户"));
    }

    private Merchant requireMerchant(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商户 ID 无效");
        }
        return merchantMapper.findById(merchantId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在"));
    }

    private MerchantWithdrawRequest requireRequest(long requestId) {
        return withdrawMapper.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提现单不存在"));
    }

    private MerchantWalletAccountDto toAccountDto(Merchant merchant) {
        MerchantWalletAccount account = accountMapper.selectById(merchant.getMerchantId());
        long balance = account == null ? 0L : value(account.getBalanceCents());
        long frozen = account == null ? 0L : value(account.getFrozenCents());
        return new MerchantWalletAccountDto(
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                merchant.getContactPhone(),
                merchant.getStatus(),
                balance,
                frozen,
                balance - frozen
        );
    }

    private MerchantWithdrawRequestDto toDto(MerchantWithdrawRequest request) {
        String merchantName = request.getMerchantName();
        if (merchantName == null || merchantName.isBlank()) {
            Merchant merchant = merchantMapper.findById(request.getMerchantId()).orElse(null);
            merchantName = merchant == null ? null : merchant.getMerchantName();
        }
        return new MerchantWithdrawRequestDto(
                request.getRequestId(),
                request.getRequestNo(),
                request.getMerchantId(),
                merchantName,
                request.getAmountCents(),
                request.getStatus(),
                request.getPayChannel(),
                request.getReviewerId(),
                request.getReviewRemark(),
                request.getReviewedAt(),
                request.getPayoutRef(),
                request.getPayoutMessage(),
                request.getPaidAt(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                request.getFeeCents() == null ? 0L : request.getFeeCents(),
                // V307 收款方快照（payeeAccountNoMask 本身已是掩码，直接透传）
                request.getPayoutAccountId(),
                request.getPayeeAccountType(),
                request.getPayeeAccountName(),
                request.getPayeeAccountNoMask(),
                request.getPayeeBankName(),
                request.getIdemKey(),
                request.getChannelOrderNo(),
                request.getChannelBatchNo()
        );
    }

    private MerchantWalletLedgerDto toLedgerDto(MerchantWalletLedger ledger) {
        return new MerchantWalletLedgerDto(
                ledger.getLedgerId(),
                ledger.getMerchantId(),
                ledger.getMerchantName(),
                ledger.getEntryType(),
                ledger.getAmountCents(),
                ledger.getBalanceAfter(),
                ledger.getFrozenAfter(),
                ledger.getRefType(),
                ledger.getRefId(),
                ledger.getRemark(),
                ledger.getCreatedAt()
        );
    }

    private static String normalizeRequestNo(String requestNo) {
        if (requestNo != null && !requestNo.isBlank()) {
            return requestNo.trim();
        }
        // 与订单/会话一致：纯数字业务单号（禁 MW- 字母前缀）
        return BizIds.nextNumeric();
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String merchantWalletLockKey(String merchantId) {
        return "merchant:wallet:" + merchantId;
    }

    private <T> T runWithMerchantWalletLock(String merchantId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(merchantWalletLockKey(merchantId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(merchantWalletLockKey(merchantId));
        }
    }
}
