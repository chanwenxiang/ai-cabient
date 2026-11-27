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
import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelLimits;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConfirmTicketStore;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.payout.WeChatTransferClient;
import com.aicabinet.trade.support.CsvCells;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Duration FUNDING_RETRY_DELAY = Duration.ofHours(1);


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MerchantWithdrawRequestMapper withdrawMapper;
    private final MerchantMapper merchantMapper;
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
    /** CB-033 平台资金池：打款前备付校验 + 打款成功登记流出（测试可传 null 跳过）。 */
    private final PlatformFundService platformFundService;

    /** CB-035：T+1 可提现解锁时刻（小时）读取；null 容忍（测试兜底默认 0 点解锁）。 */
    private final SystemConfigService systemConfigService;
    private final MerchantFeaturePackService merchantFeaturePackService;
    /** CB-086：欠款账龄（钱包列表补「账龄」列；仅余额为负时查）。 */
    private final MerchantWalletArrearsService walletArrearsService;
    private final MerchantScopeService merchantScopeService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;
    /** MA-407 确认收款模式：待用户确认票据（24h UX 凭证，放 Redis）。 */
    private final PayoutConfirmTicketStore confirmTicketStore;
    /** 服务号资金通知：提现成功/终态失败；null 容忍（既有单测不关心通知）。 */
    private final NotificationService notificationService;
    private final OpsUserMerchantMapper userMerchantRepository;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final MerchantWithdrawService self;

    private static final String BIZ_MERCHANT_WITHDRAW = "MERCHANT_WITHDRAW";
    private static final String BIZ_WALLET_ADJUST = "MERCHANT_WALLET_ADJUST";
    public MerchantWithdrawService(MerchantWithdrawRequestMapper withdrawMapper,
                                   MerchantMapper merchantMapper,
                                   MerchantWalletService merchantWalletService,
                                   MerchantWithdrawPayoutService payoutService,
                                   MerchantWithdrawProperties properties,
                                   PayoutAccountService payoutAccountService,
                                   PayoutAccountMapper payoutAccountMapper,
                                   MerchantFeaturePackService merchantFeaturePackService,
                                   MerchantWalletArrearsService walletArrearsService,
                                   MerchantScopeService merchantScopeService,
                                   PermissionService permissionService,
                                   AdminAuditService auditService,
                                   DistributedLockService distributedLockService,
                                   ApprovalWorkflowService approvalWorkflowService,
                                   WithdrawPolicyResolver policy,
                                   PayoutChannelRegistry payoutChannelRegistry,
                                   OrderRevenueSplitMapper orderRevenueSplitMapper,
                                   WithdrawEligibilityService withdrawEligibilityService,
                                   PlatformFundService platformFundService,
                                   SystemConfigService systemConfigService,
                                   PayoutConfirmTicketStore confirmTicketStore,
                                   NotificationService notificationService,
                                   OpsUserMerchantMapper userMerchantRepository,
                                   @Lazy MerchantWithdrawService self) {
        this.withdrawMapper = withdrawMapper;
        this.merchantMapper = merchantMapper;
        this.merchantWalletService = merchantWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
        this.payoutAccountService = payoutAccountService;
        this.payoutAccountMapper = payoutAccountMapper;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.walletArrearsService = walletArrearsService;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.policy = policy;
        this.payoutChannelRegistry = payoutChannelRegistry;
        this.orderRevenueSplitMapper = orderRevenueSplitMapper;
        this.withdrawEligibilityService = withdrawEligibilityService;
        this.platformFundService = platformFundService;
        this.systemConfigService = systemConfigService;
        this.confirmTicketStore = confirmTicketStore;
        this.notificationService = notificationService;
        this.userMerchantRepository = userMerchantRepository;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageResult<MerchantWalletAccountDto> listAccounts(Long operatorId, String keyword, int page, int size,
                                                             boolean onlyNegative) {
        permissionService.requireAnyPermission(operatorId,
                PERM_OPS_MERCHANT_WITHDRAW_LIST, PERM_OPS_FINANCE_VIEW, "ops:merchant:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        if (allowed.isEmpty()) {
            return new PageResult<>(List.of(), p, s, 0);
        }
        // CB-086：欠款名单——先按负余额账户收窄到商户 id 集，再走原有「权限范围 + 关键字」查询，
        // 保证分页/总数与其它 tab 同源（不是前端过滤当前页）。
        if (onlyNegative) {
            Set<String> negativeIds = walletArrearsService.negativeAccounts().stream()
                    .map(MerchantWalletArrearsService.Arrears::merchantId)
                    .filter(allowed::contains)
                    .collect(java.util.stream.Collectors.toSet());
            if (negativeIds.isEmpty()) {
                return new PageResult<>(List.of(), p, s, 0);
            }
            allowed = negativeIds;
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
        return merchantWalletService.listRecentLedger(merchantId, limit).stream()
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
                    false, null, null, null, null, null, null, List.of(), List.of());
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
                    false, null, null, null, null, null, null, List.of(), List.of());
        }
        // 只读视图不建户（与 LineWithdrawService.merchantOverview 同口径）：缺户按 0 展示。
        // 走 ensureAccount 会在这个 readOnly 事务里 INSERT ⇒ PG 25006 ⇒ 门户钱包页对新商户恒 500，
        // 而且 GET 会抢钱包写锁。建档/入账路径（validateAmount 等）继续 ensure。
        MerchantWalletAccount account = merchantWalletService.findAccount(merchantId);
        long balance = value(account == null ? null : account.getBalanceCents());
        long frozen = value(account == null ? null : account.getFrozenCents());
        // CB-035：展示口径与 V308 提现闸门同口径——可提 = 余额 − 冻结 − 待结算（T+1 未到期）
        long pendingSettle = pendingSettleAmount(merchantId);
        return new MerchantWalletOverviewDto(
                true,
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                balance,
                frozen,
                Math.max(0, balance - frozen - pendingSettle),
                pendingSettle,
                merchantWalletService.listRecentLedger(merchantId, 10).stream()
                        .map(this::toLedgerDto)
                        .toList(),
                withdrawMapper.findByMerchantIdOrderByCreatedAtDesc(merchantId, 10).stream()
                        .map(this::toDto)
                        .toList()
        );
    }

    /**
     * MA-166：大额调账审批（MERCHANT_WALLET_ADJUST 实例）的人工审批入口。
     * 调账资金在发起时已即时生效（事后留痕语义），本审批驱动实例状态机：
     * 通过=APPROVED（留痕完结）；驳回=REJECTED（留痕+审计提示运营人工评估资金冲回，
     * 不自动反向调账——钱包余额可能已被消耗，自动冲回有透支风险）。
     *
     * MA-166/F1 修（对齐 completeReview:354-359）：
     * ① 实例不存在（无定义/已禁用/未发起）⇒ 409「审批流未发起」；
     * ② 实例已终结 ⇒ 409「当前状态不可审核」；
     * ③ 通过/驳回的实效不再无条件回报 APPROVED/REJECTED，而是读实例真实状态——
     *    双签流里运营节点通过后实例仍 PENDING（移交财务复核），审计留痕与界面显示
     *    从此与实例状态机一致，不再出现「报表说 APPROVED、库里还 PENDING」的失真。
     * 幂等重复点：终态再点挡 409；跨节点再点由工作流「非当前处理人」挡 403（fail-loud）。
     */
    public Map<String, Object> reviewAdjustment(Long operatorId, String bizId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_WITHDRAW_REVIEW);
        // ② 实例存在性 + 终态守卫。无实例时 completeApproved/completeRejected 是静默 no-op，
        //    必须挡在调用前，否则仍会走假 APPROVED/REJECTED 并写假审计行。
        java.util.Optional<String> before =
                approvalWorkflowService.instanceStatus(BIZ_WALLET_ADJUST, bizId);
        if (before.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "审批流未发起（审批定义为空或已禁用），无法" + (approve ? "通过" : "驳回") + "；如需退回冻结资金请走驳回");
        }
        if (!"PENDING".equals(before.get())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可审核");
        }
        if (approve) {
            approvalWorkflowService.completeApproved(operatorId, BIZ_WALLET_ADJUST, bizId, trim(remark));
        } else {
            approvalWorkflowService.completeRejected(operatorId, BIZ_WALLET_ADJUST, bizId, trim(remark));
        }
        // ③ 读实例真实状态（双签：本节点过 → 实例移交下一节点仍 PENDING，非 APPROVED）
        String status = approvalWorkflowService.instanceStatus(BIZ_WALLET_ADJUST, bizId).orElse("");
        auditService.appendLog(operatorId, BIZ_WALLET_ADJUST + "_REVIEW", BIZ_WALLET_ADJUST, bizId,
                "结果=" + status + "；备注=" + trim(remark));
        return Map.of("bizId", bizId, "status", status);
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
        // CONCURRENCY: review() 持商户钱包分布式锁，本方法仅做审核状态短事务。
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!"PENDING_REVIEW".equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可审核");
        }
        if (approve && approvalWorkflowService.instanceStatus(
                BIZ_MERCHANT_WITHDRAW, String.valueOf(requestId)).isEmpty()) {
            // 同 G9-4 §7-B：无实例时 completeApproved 是静默 no-op ⇒ 会 200 却永远停在 PENDING_REVIEW
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "审批流未发起（审批定义为空或已禁用），无法通过；如需退回冻结资金请走驳回");
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
            notifyWithdrawAfterCommit(request, false,
                    "提现申请已驳回" + (trim(remark).isEmpty() ? "" : "：" + trim(remark)));
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
        MerchantWithdrawRequest pre = requireRequest(requestId);
        merchantScopeService.requireMerchantAccess(operatorId, pre.getMerchantId());
        return runWithMerchantWalletLock(pre.getMerchantId(), () -> {
            // AU-007 同形修正：闸门以<b>锁内重查</b>为准。原先判的是加锁前读的快照 ——
            // 等锁期间并发方可能已把单据推到 PAID/FAILED，用旧快照放行会多一次渠道调用与记账。
            if (!Set.of(STATUS_APPROVED, "FAILED").contains(requireRequest(requestId).getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
            }
            auditService.appendLog(operatorId, "MERCHANT_WITHDRAW_PAYOUT", BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(requestId), "打款金额(分)=" + pre.getAmountCents());
            return self.executePayout(requestId);
        });
    }

    /**
     * 打款失败后取消：解冻资金并标记 CANCELLED，不可再重试打款。
     *
     * <p>AU-007（审计 B-1 F-02）两层修正：
     * ① 改「先锁后事务」—— 原方法级 {@code @Transactional} 在事务内取分布式锁，
     *    锁在提交前释放，并发方能拿到锁读到未提交的旧状态；
     * ② 状态守卫在<b>锁内重查</b>单据 —— 原先用加锁前快照判 FAILED，
     *    与 payout 并发时可对已 PAID 单重复 releaseFrozen（出款后又放钱，资损可复现）。</p>
     */
    public MerchantWithdrawRequestDto cancelFailed(Long operatorId, long requestId, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_WITHDRAW_REVIEW);
        MerchantWithdrawRequest pre = requireRequest(requestId);
        merchantScopeService.requireMerchantAccess(operatorId, pre.getMerchantId());
        return runWithMerchantWalletLock(pre.getMerchantId(),
                () -> self.cancelFailedLocked(requestId, operatorId, remark));
    }

    /** {@link #cancelFailed} 的事务体：只在持有钱包锁后进入，行状态以锁内重查为准。 */
    @Transactional
    public MerchantWithdrawRequestDto cancelFailedLocked(long requestId, Long operatorId, String remark) {
        MerchantWithdrawRequest request = requireRequest(requestId);
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
        // R2-B2：幂等回放查询必须在 validateAmount **之前**。
        // 首笔已冻结入库、客户端 20s 超时按设计保留 requestNo 重试 —— 此时余额已被自己冻结，
        // validateAmount 必然报「可用余额不足/超限额」，前端当业务失败清键，等于幂等契约在最常见
        // 场景（超时重试）下失效（不会双扣，但钱其实已提、界面报错）。回放命中只需归属+金额，
        // 不依赖收款账户，所以查询先走、账户与限额校验留给真正新建的分支。
        String no = normalizeRequestNo(requestNo);
        var existing = withdrawMapper.findByRequestNo(no);
        if (existing.isPresent()) {
            MerchantWithdrawRequest hit = existing.get();
            // AU-006（审计 B-1 F-01）：requestNo 全局唯一 ⇒ 命中必须校验「归属+金额」。
            // 原先直接返回命中单：他人 requestNo 泄露后，B 商户提交同号会拿到 A 的
            // 提现 DTO（金额/收款方/打款流水号跨租户泄露），且 B 自己的请求被静默吞。
            // 同商户同金额才算真重放，其余一律显式 409（让调用方换单号，不吞不泄）。
            if (!merchant.getMerchantId().equals(hit.getMerchantId())
                    || value(hit.getAmountCents()) != amountCents) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "提现单号已被其它请求占用，请更换 requestNo 后重新提交");
            }
            // R4-D4：终态单（驳回/取消）不再回放 —— 客户端会把它当「已提交」假成功。
            // PAID/PAYING/FAILED 回放仍是诚实语义（资金动作由打款状态机看护，不在此拦）。
            if ("REJECTED".equals(hit.getStatus()) || "CANCELLED".equals(hit.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该 requestNo 对应的提现单已"
                                + ("REJECTED".equals(hit.getStatus()) ? "驳回" : "取消")
                                + "（单 " + hit.getRequestId() + "），请更换 requestNo 重新提交");
            }
            return PayoutGate.done(toDto(hit));
        }
        // V307：申请时锁定收款账户（不指定则用默认）—— 快照后历史不可变。
        // 🔴 必须在 validateAmount 之前：限额校验依赖账户的 channel 与 accountId
        //    （单收款人单日 / 单通道当日总额两个维度都要用到）。
        PayoutAccount payeeAccount = resolvePayeeForApply(merchant.getMerchantId());
        validateAmount(merchant.getMerchantId(), amountCents, payeeAccount);
        Instant now = Instant.now();
        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestNo(no);
        request.setMerchantId(merchant.getMerchantId());
        request.setMerchantName(merchant.getMerchantName());
        request.setAmountCents(amountCents);
        request.setFeeCents(WithdrawFeeCalculator.computeFeeCents(
                amountCents, policy.merchantFeeCents(), policy.merchantFeeBpsFor(merchant.getMerchantId()),
                policy.merchantFeeCapCents()));
        // 🔴 渠道取自「所选收款账户」，不再是 mock 开关的字面量：一个商户可按账户走不同通道
        request.setPayChannel(payeeAccount.getChannel());
        applyPayeeSnapshot(request, payeeAccount);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= policy.merchantReviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
            // MA-073：提现申请全族唯一无留痕的动作（审核/打款/取消都有）⇒ 事后无法回答「谁提的这笔」
            auditService.appendLog(submitterUserId, "MERCHANT_WITHDRAW_APPLY", BIZ_MERCHANT_WITHDRAW,
                    String.valueOf(request.getRequestId()),
                    "amountCents=" + amountCents + ",requestNo=" + request.getRequestNo()
                            + ",status=" + request.getStatus());
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
        // MA-073：低额自动通过的申请同样要留痕（此分支不落 review，只有本行能回答「谁提的」）
        auditService.appendLog(submitterUserId, "MERCHANT_WITHDRAW_APPLY", BIZ_MERCHANT_WITHDRAW,
                String.valueOf(request.getRequestId()),
                "amountCents=" + amountCents + ",requestNo=" + request.getRequestNo()
                        + ",status=" + request.getStatus() + "（低于阈值自动通过）");
        applyIdemKey(request);
        merchantWalletService.freezeForWithdraw(merchant.getMerchantId(), amountCents,
                WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
        return PayoutGate.needPayout(toDto(request));
    }

    /**
     * 写打款幂等键。<b>必须在 insert 之后</b> —— 键里含 requestId（自增主键）。
     *
     * <p>形态：{@code MW:<requestId>}。只由不变业务要素构成，同一单据的不同重试
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
        return executePayout(requestId, false);
    }

    /**
     * @param channelAuthority true=自动恢复重试，不以本地资金池余额拦截，完全由渠道结果裁决
     */
    public MerchantWithdrawRequestDto executePayout(long requestId, boolean channelAuthority) {
        MerchantWithdrawRequest pending = requireRequest(requestId);
        // 人工打款仍用本地台账做提前拦截；自动恢复重试由渠道 NOT_ENOUGH/SUCCESS 裁决。
        if (!channelAuthority && platformFundService != null) {
            long fee = pending.getFeeCents() == null ? 0L : pending.getFeeCents();
            long netCents = Math.max(0L, pending.getAmountCents() - fee);
            Optional<String> blocked = platformFundService.gatePayout(
                    pending.getPayChannel(), netCents, requestId);
            if (blocked.isPresent()) {
                Instant now = Instant.now();
                String reason = blocked.get();
                withdrawMapper.markFundingBlocked(requestId, reason, now.plus(FUNDING_RETRY_DELAY), now);
                auditService.appendLog(null, "MERCHANT_WITHDRAW_PAYOUT_BLOCKED", BIZ_MERCHANT_WITHDRAW,
                        String.valueOf(requestId),
                        "备付不足跳过打款（保留原状态，1 小时后按渠道结果自动重试）；金额(分)=" + netCents
                                + "；原因=" + reason);
                return toDto(requireRequest(requestId));
            }
        }
        MerchantWithdrawRequest paying = self.markPaying(requestId);
        Merchant merchant = requireMerchant(paying.getMerchantId());
        PayoutAccount account = resolveSnapshotAccount(paying);
        MerchantWithdrawPayoutService.PayoutResult result = payoutService.payout(paying, merchant, account);
        MerchantWithdrawRequestDto dto = self.finalizePayout(requestId, result);
        // CB-033：打款成功后登记平台资金池流出（放在 finalize 事务提交后，台账坏了不影响打款事实）
        if (result.success() && platformFundService != null) {
            long fee = paying.getFeeCents() == null ? 0L : paying.getFeeCents();
            platformFundService.recordPayoutQuietly(result.payChannel(),
                    Math.max(0L, paying.getAmountCents() - fee), requestId);
        }
        return dto;
    }

    /** 每小时扫描备付失败单并自动重试；渠道结果作为权威，本地台账不拦自动重试。 */
    public int retryFundingBlockedWithdraws() {
        Instant now = Instant.now();
        List<MerchantWithdrawRequest> candidates = withdrawMapper.findFundingRetryCandidates(now);
        int attempted = 0;
        for (MerchantWithdrawRequest candidate : candidates) {
            try {
                attempted += runWithMerchantWalletLock(candidate.getMerchantId(), () -> {
                    MerchantWithdrawRequest current = requireRequest(candidate.getRequestId());
                    if (!Set.of(STATUS_APPROVED, "FAILED").contains(current.getStatus())
                            || current.getLastPayoutBlockReason() == null
                            || current.getLastPayoutBlockReason().isBlank()
                            || current.getNextPayoutRetryAt() == null
                            || current.getNextPayoutRetryAt().isAfter(Instant.now())) {
                        return 0;
                    }
                    auditService.appendLog(null, "MERCHANT_WITHDRAW_PAYOUT_RETRY", BIZ_MERCHANT_WITHDRAW,
                            String.valueOf(current.getRequestId()),
                            "备付恢复自动重试；渠道结果作为权威；金额(分)=" + current.getAmountCents()
                                    + "；上次原因=" + current.getLastPayoutBlockReason());
                    self.executePayout(current.getRequestId(), true);
                    return 1;
                });
            } catch (PayoutChannel.PayoutPendingException e) {
                log.warn("funding retry remains PAYING requestId={} orderNo={} reason={}",
                        candidate.getRequestId(), e.channelOrderNo(), e.getMessage());
            } catch (Exception e) {
                log.warn("funding retry failed requestId={} err={}", candidate.getRequestId(), e.toString());
            }
        }
        return attempted;
    }

    private static boolean isFundingFailure(MerchantWithdrawPayoutService.PayoutResult result) {
        String message = result.message() == null ? "" : result.message().toUpperCase(Locale.ROOT);
        return message.contains("NOT_ENOUGH") || message.contains("备付金不足");
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
        // 🔴 不再允许 PAYING → PAYING：那等于「本地拦不住重复出款，只剩渠道幂等键」。
        //    提现锁租约 60s 且无看门狗，渠道 I/O 挂过 60s 后第二个请求能拿到锁并重入这里。
        //    陈旧 PAYING 本来就没有合法调用方（review 只发 APPROVED、payout 只发 APPROVED/FAILED），
        //    它的正确处置是 failStalePayingWithdraws：MOCK 置 FAILED 后走 payout 重试，真渠道转人工核对。
        if (!Set.of(STATUS_APPROVED, "FAILED").contains(previousStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        Instant now = Instant.now();
        // CONCURRENCY: 条件 UPDATE 抢出款权：单据没有 @Version，读-改-写之间有窗口；
        // 抢不到（0 行）说明并发方已把状态推走，此时向渠道转账就是重复出款。
        if (withdrawMapper.claimForPayout(requestId, previousStatus, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该提现单已有出款在进行中，请稍后刷新查看结果");
        }
        request.setStatus("PAYING");
        request.setUpdatedAt(now);
        request.setNextPayoutRetryAt(null);
        request.setLastPayoutBlockReason(null);
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
        // CONCURRENCY: 条件 UPDATE —— 出款权由上游 markPaying 的 claimForPayout（WHERE status=?）抢占，
        // 本方法只回写「本线程刚抢到的那一行」；awaitingUserConfirm 分支写的 PAYING 与原状态同值（幂等），
        // 把单据从 PAYING 推到终态一律走 finalizeIfPaying（UPDATE ... WHERE status='PAYING'，0 行即放弃）。
        MerchantWithdrawRequest request = requireRequest(requestId);
        Instant now = Instant.now();
        request.setPayChannel(result.payChannel());
        request.setPayoutRef(result.payoutRef());
        request.setPayoutMessage(trim(result.message()));
        request.setUpdatedAt(now);
        if (result.awaitingUserConfirm()) {
            // MA-407 确认收款模式：渠道已建单并锁定资金，等用户确认。
            // 🔴 三个「不做」：不落 PAID（钱没到账）、不释放冻结（钱已锁在渠道）、不解幂等键
            //    （用户确认后的回调/查单还要靠 out_bill_no 定位这笔）。
            request.setStatus("PAYING");
            if (withdrawMapper.markAwaitingUserConfirm(requestId, now, result.payChannel(),
                    result.payoutRef(), trim(result.message())) == 0) {
                // 并发回调/查单已推走终态：不能为旧结果存确认票据。
                log.info("sync payout awaiting-confirm lost race: requestId={}", requestId);
                return toDto(requireRequest(requestId));
            }
            // 🔴 票据 key 必须是**渠道单号（idem_key）**，不是 transfer_bill_no：
            //    读取点 confirmTicket() 用 request.getIdemKey() 反查，存的是 transfer_bill_no
            //    会导致「钱锁在渠道、商户却永远拿不到确认入口」（2026-11-23 复核发现）。
            confirmTicketStore.save(MerchantWithdrawPayoutService.billNoOf(request),
                    new PayoutConfirmTicketStore.Ticket(
                            requestId, result.payChannel(), result.confirmPackage(),
                            now.plusSeconds(24 * 3600)));
            request.setNextPayoutRetryAt(null);
            request.setLastPayoutBlockReason(null);
            return toDto(request);
        }
        if (result.success()) {
            request.setStatus("PAID");
            request.setPaidAt(now);
            request.setNextPayoutRetryAt(null);
            request.setLastPayoutBlockReason(null);
            int finalized = withdrawMapper.finalizeIfPaying(requestId, "PAID", now,
                    result.payoutRef(), trim(result.message()));
            if (finalized == 0) {
                log.info("sync payout finalize lost race: requestId={}, target=PAID", requestId);
                return toDto(requireRequest(requestId));
            }
            // V308：拆分记账 —— 渠道实发净额，手续费单列一行（合计仍等于毛额）
            long feeCents = request.getFeeCents() == null ? 0L : request.getFeeCents();
            merchantWalletService.consumeFrozenSplit(
                    request.getMerchantId(),
                    WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), feeCents),
                    feeCents,
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款成功");
            notifyWithdrawAfterCommit(request, true, null);
            return toDto(request);
        }
        request.setStatus("FAILED");
        boolean fundingFailure = isFundingFailure(result);
        boolean firstFundingFailure = fundingFailure
                && (request.getPayoutRetryCount() == null || request.getPayoutRetryCount() == 0);
        String blockReason = fundingFailure ? trim(result.message()) : null;
        Instant nextRetryAt = fundingFailure ? now.plus(FUNDING_RETRY_DELAY) : null;
        request.setLastPayoutBlockReason(blockReason);
        request.setNextPayoutRetryAt(nextRetryAt);
        if (fundingFailure) {
            request.setPayoutRetryCount((request.getPayoutRetryCount() == null ? 0 : request.getPayoutRetryCount()) + 1);
        }
        if (withdrawMapper.markPayoutFailed(requestId, now, result.payoutRef(), trim(result.message()),
                blockReason, nextRetryAt) == 0) {
            // 并发方已落终态：绝不能拿旧失败结果再释放一次冻结。
            log.info("sync payout failure lost race: requestId={}", requestId);
            return toDto(requireRequest(requestId));
        }
        // 打款失败即释放冻结，避免冻结悬挂（与人工 cancelFailed 解冻口径一致；日限额统计仍排除终态）
        merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                WITHDRAW, String.valueOf(request.getRequestId()), "提现打款失败释放");
        // 首次备付失败通知一次；后续每小时恢复重试保持静默，成功后再发成功通知。
        if (!fundingFailure || firstFundingFailure) {
            notifyWithdrawAfterCommit(request, false, trim(result.message()));
        }
        return toDto(request);
    }

    /**
     * <b>渠道终态汇聚点</b>（MA-407）：把 PAYING 单据按渠道结果落 PAID / FAILED。
     *
     * <p>三个入口共用这一处（铁律：扣款/释冻类闸放汇聚点，不放各入口）：
     * ① 微信转账结果回调 {@code /api/v2/payout/wechat/transfer-notify}；
     * ② 定时查单收口（回调丢失 / 未配 notify_url）；
     * ③ 商户点「去确认收款」时的即时查单（用户刚确认完，回调可能还没到）。
     *
     * <p>🔴 幂等与并发：靠 {@code UPDATE ... WHERE status='PAYING'}，0 行即整条放弃
     * （已有别人落过终态），绝不重复解冻/重复记账。
     *
     * @return true=本次真的推进了状态
     */
    @Transactional
    public boolean finalizeFromChannel(long requestId, boolean success, String channelOrderNo,
                                       String message) {
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!"PAYING".equals(request.getStatus())) {
            log.info("channel finalize skipped (not PAYING): requestId={}, status={}",
                    requestId, request.getStatus());
            return false;
        }
        Instant now = Instant.now();
        String status = success ? "PAID" : "FAILED";
        String ref = channelOrderNo != null && !channelOrderNo.isBlank()
                ? channelOrderNo : request.getPayoutRef();
        if (withdrawMapper.finalizeIfPaying(requestId, status, now, ref, trim(message)) == 0) {
            log.info("channel finalize lost race: requestId={}, target={}", requestId, status);
            return false;
        }
        long feeCents = request.getFeeCents() == null ? 0L : request.getFeeCents();
        if (success) {
            merchantWalletService.consumeFrozenSplit(request.getMerchantId(),
                    WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), feeCents), feeCents,
                    WITHDRAW, String.valueOf(requestId), "提现到账（渠道确认收款）");
        } else {
            merchantWalletService.releaseFrozen(request.getMerchantId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(requestId), "提现打款失败释放（渠道终态）");
        }
        // 票据按渠道单号（idem_key）存，故清理也必须用同一个键（用 payoutRef 会残留到 TTL 到期）
        confirmTicketStore.clear(MerchantWithdrawPayoutService.billNoOf(request));
        auditService.appendLog(0L,
                success ? "MERCHANT_WITHDRAW_PAYOUT_CONFIRMED" : "MERCHANT_WITHDRAW_PAYOUT_CHANNEL_FAILED",
                BIZ_MERCHANT_WITHDRAW, String.valueOf(requestId),
                "渠道终态=" + status + "；渠道单号=" + ref + "；" + trim(message));
        if (success && platformFundService != null) {
            platformFundService.recordPayoutQuietly(request.getPayChannel(),
                    Math.max(0L, request.getAmountCents() - feeCents), requestId);
        }
        notifyWithdrawAfterCommit(request, success, message);
        return true;
    }

    /**
     * 提现结果通知在事务提交后发：NotificationService 可能同步调微信 HTTP，
     * 放在扣款/释冻事务里会长时间持锁。重复回调/CAS 未推进的路径不会走到这里。
     */
    private void notifyWithdrawAfterCommit(MerchantWithdrawRequest request, boolean success,
                                           String failureMessage) {
        if (notificationService == null || userMerchantRepository == null) {
            return;
        }
        // NotificationService 统一负责站内信同事务落库与外部渠道 afterCommit；这里不能再包一层。
        notifyWithdrawOperators(request, success, failureMessage);
    }

    private void notifyWithdrawOperators(MerchantWithdrawRequest request, boolean success,
                                         String failureMessage) {
        try {
            List<OpsUserMerchant> binds = userMerchantRepository.findActiveByMerchantId(request.getMerchantId());
            if (binds.isEmpty()) {
                return;
            }
            // 模板 character_string5 只接受数字/字母/符号，不能填中文商户名。
            String account = request.getMerchantId();
            long amount = request.getAmountCents() == null ? 0L : request.getAmountCents();
            long fee = request.getFeeCents() == null ? 0L : request.getFeeCents();
            String requestTime = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
                    .withZone(ZONE)
                    .format(request.getCreatedAt() == null ? Instant.now() : request.getCreatedAt());
            Map<String, String> params = new java.util.LinkedHashMap<>();
            params.put("amount", yuan(amount));
            params.put("fee", yuan(fee));
            params.put("receivedAmount", yuan(Math.max(0L, amount - fee)));
            params.put("account", account);
            params.put("requestTime", requestTime);
            params.put("failureReason", success ? "" : normalizeWithdrawFailureReason(failureMessage));
            for (OpsUserMerchant bind : binds) {
                if (bind.getUserId() == null) {
                    continue;
                }
                try {
                    if (success) {
                        notificationService.send("MERCHANT", bind.getUserId(), request.getMerchantId(),
                                "merchant_withdraw_success", params, "WITHDRAW_SUCCESS",
                                String.valueOf(request.getRequestId()));
                    } else {
                        notificationService.send("MERCHANT", bind.getUserId(), request.getMerchantId(),
                                "merchant_withdraw_failed", params, "WITHDRAW_FAILED",
                                String.valueOf(request.getRequestId()));
                    }
                } catch (Exception e) {
                    log.warn("withdraw notification failed requestId={} userId={}: {}",
                            request.getRequestId(), bind.getUserId(), e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("withdraw notification dispatch failed requestId={}", request.getRequestId(), e);
        }
    }

    /** 与「提现失败提醒」模板审核通过的枚举逐字对齐；未知渠道错误统一落「其他原因」。 */
    private static String normalizeWithdrawFailureReason(String message) {
        String raw = message == null ? "" : message.toUpperCase(Locale.ROOT);
        if (raw.contains("NOT_ENOUGH") || raw.contains("备付金不足")) {
            return "备付金不足";
        }
        if (raw.contains("账户") || raw.contains("银行卡") || raw.contains("ACCOUNT") || raw.contains("BANK")) {
            return "账户信息有误";
        }
        if (raw.contains("拒收") || raw.contains("REJECT")) {
            return "银行卡拒收";
        }
        if (raw.contains("渠道") || raw.contains("SYSTEM") || raw.contains("FAIL")) {
            return "其他原因";
        }
        return "其他原因";
    }

    /**
     * 微信转账结果回调收口（MA-407 确认收款模式）。
     *
     * <p>🔴 <b>为什么不放在控制器里查库</b>：`..api..` 不许依赖 `..mapper..`
     * （{@code TradeArchitectureTest.controllersShouldNotDependOnMappers}）。控制器只负责验签与
     * 抽字段，定位单据 / 判终态 / 落账 / 留痕全部收在这里——顺带保证「回调」与「定时查单」
     * 走的是同一套终态语义（都进 {@link #finalizeFromChannel} 的条件 UPDATE）。
     *
     * @return 命中的提现单 requestId；未命中返回 empty（已留痕，调用方回 2xx 免得微信反复重投）
     */
    public Optional<Long> handleTransferNotify(String outBillNo, String state, String transferBillNo) {
        if (outBillNo == null || outBillNo.isBlank()) {
            return Optional.empty();
        }
        Optional<MerchantWithdrawRequest> hit = withdrawMapper.findByIdemKey(outBillNo);
        if (hit.isEmpty()) {
            log.warn("wechat transfer notify 未匹配到提现单: outBillNo={}, state={}", outBillNo, state);
            auditService.appendLog(null, "WECHAT_PAYOUT_NOTIFY_UNMATCHED", BIZ_MERCHANT_WITHDRAW,
                    outBillNo, "转账结果通知 state=" + state + "（无对应提现单）");
            return Optional.empty();
        }
        long requestId = hit.get().getRequestId();
        // 终态判定走渠道状态的唯一真值源（MA-409）：此前这里手抄了一份字面量且含 CANCELING
        // ⇒ 渠道还在「撤销中」就落 FAILED 释放冻结。CANCELING 是非终态，等它走到 CANCELLED 再收口。
        if (WeChatTransferClient.isSuccessState(state)) {
            finalizeFromChannel(requestId, true, transferBillNo, "微信转账到账（用户已确认收款）");
        } else if (WeChatTransferClient.isTerminalFailState(state)) {
            finalizeFromChannel(requestId, false, transferBillNo, "微信转账终态=" + state + "（回调）");
        } else {
            // 非终态（WAIT_USER_CONFIRM / ACCEPTED / PROCESSING / TRANSFERING / CANCELING）与未知 state：
            // 不动单据，等下一次通知或定时查单——这里绝不能当失败处理（会误释放冻结）。
            log.info("wechat transfer notify 非终态忽略: requestId={}, state={}", requestId, state);
        }
        return Optional.of(requestId);
    }

    /**
     * 商户取「去确认收款」票据（MA-407 确认模式）。
     *
     * <p>顺带做一次<b>即时查单收口</b>：用户可能刚在微信里确认完、回调还没到，
     * 此时本地仍是 PAYING。查单发现已成功就当场落 PAID 并返回「已到账」，
     * 免得商户反复点确认（微信侧会回「单据已终态」）。
     */
    public PayoutConfirmTicket confirmTicket(Long userId, long requestId) {
        String merchantId = resolveMerchantId(userId, null);
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!merchantId.equals(request.getMerchantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该提现单");
        }
        // 与写入侧（finalizePayout）同一个键：渠道单号/幂等键，历史单据缺键时按同一规则补齐
        String billNo = MerchantWithdrawPayoutService.billNoOf(request);
        weChatQueryClosure(request, billNo);
        MerchantWithdrawRequest fresh = requireRequest(requestId);
        if (!"PAYING".equals(fresh.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该提现单当前状态为 " + fresh.getStatus() + "，无需再确认收款");
        }
        PayoutConfirmTicketStore.Ticket ticket = confirmTicketStore.find(billNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "确认入口已过期或已使用，请稍后刷新提现记录"));
        return new PayoutConfirmTicket(fresh.getRequestId(), ticket.packageInfo(), ticket.expireAt(),
                fresh.getAmountCents(), fresh.getPayChannel());
    }

    /** 微信单查询收口（走通道抽象，未实现查单的通道返回 empty ⇒ 保持现状转人工）。 */
    private void weChatQueryClosure(MerchantWithdrawRequest request, String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            return;
        }
        try {
            Optional<PayoutChannel.BillState> state = payoutChannelRegistry.find(request.getPayChannel())
                    .flatMap(channel -> channel.queryBill(idemKey));
            if (state.isEmpty()) {
                return;
            }
            PayoutChannel.BillState bill = state.get();
            if (bill.success()) {
                self.finalizeFromChannel(request.getRequestId(), true, bill.channelOrderNo(),
                        "查单确认已到账");
            } else if (bill.failed()) {
                self.finalizeFromChannel(request.getRequestId(), false, bill.channelOrderNo(),
                        "查单确认渠道终态=" + bill.rawState() + "（" + bill.detail() + "）");
            }
        } catch (RuntimeException e) {
            log.warn("payout query closure failed requestId={} err={}", request.getRequestId(), e.toString());
        }
    }

    /** 确认收款票据（对外返回体；不含任何敏感账号信息）。 */
    public record PayoutConfirmTicket(Long requestId, String packageInfo, Instant expireAt,
                                      Long amountCents, String payChannel) {
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
     * PAYING 超时的兜底扫描（MA-407 后分两步）。
     *
     * <p>🔴 <b>本方法刻意不加 {@code @Transactional}</b>：第 ① 步要向渠道查单（HTTP I/O），
     * 渠道调用绝不能包在数据库事务里（长事务 + 锁等待，`check:tx-boundary-channel-call` 也会红）。
     *
     * <p>处置顺序：
     * <ol>
     *   <li><b>先查单</b>：确认模式下的 PAYING 单据大多是「用户在微信里确认了、回调还没到」，
     *       查单能直接收口成 PAID / FAILED —— 这是把「卡 PAYING」变成「自动到账」的关键一步；</li>
     *   <li>查不到终态才走原策略：MOCK 置 FAILED 解冻（可重试）；真实渠道保持 PAYING 转人工
     *       （F3：回执丢失时自动失败 = 双重支出）。</li>
     * </ol>
     *
     * @return 本次推进（落终态）的单数
     */
    public int failStalePayingWithdraws() {
        Instant cutoff = Instant.now().minus(PAYING_TIMEOUT_MINUTES, java.time.temporal.ChronoUnit.MINUTES);
        List<MerchantWithdrawRequest> stale =
                withdrawMapper.findByStatusAndUpdatedAtBefore("PAYING", cutoff);
        int progressed = 0;
        for (MerchantWithdrawRequest staleRequest : stale) {
            try {
                // ① 渠道查单收口（事务外）
                if ("PAYING".equals(staleRequest.getStatus())) {
                    weChatQueryClosure(staleRequest, staleRequest.getIdemKey());
                }
                // ② 仍未终态才按超时策略处理（独立短事务 + 钱包锁）
                if (self.failSingleStalePaying(staleRequest.getRequestId())) {
                    progressed++;
                }
            } catch (Exception e) {
                log.warn("stale PAYING merchant withdraw sweep failed requestId={} err={}",
                        staleRequest.getRequestId(), e.toString());
            }
        }
        return progressed;
    }

    @Transactional
    public boolean failSingleStalePaying(long requestId) {
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
            // 🔴 条件 UPDATE：与渠道回调/查单收口并发时，拿快照直写会覆盖已落终态的单
            if (withdrawMapper.finalizeIfPaying(requestId, "FAILED", Instant.now(), request.getPayoutRef(),
                    WithdrawPayoutPolicy.payingTimeoutFailMessage(PAYING_TIMEOUT_MINUTES)) == 0) {
                return false;
            }
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
        // CB-035：解锁时刻可配（0-23 点，默认 0=次日零点即解冻）。只挪「能提」的时刻，
        // 不动日结/入账/账单展示——settleAfter 仍=T+1。
        int unlockHour = systemConfigService == null
                ? 0
                : systemConfigService.getInt(SystemConfigService.MERCHANT_WITHDRAW_SETTLE_UNLOCK_HOUR, 0);
        return orderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable(
                merchantId, unlockedThroughDate(LocalDate.now(ZONE), java.time.LocalTime.now(ZONE), unlockHour));
    }

    /**
     * CB-035：settleAfter ≤ 该日期的分账已可提现（闸门 SQL 口径为 {@code gt(settleAfter, 该日期)}）。
     *
     * <p>unlockHour 点前，昨天的销售款（settleAfter=今天）尚未解锁 ⇒ 返回昨天；
     * 到点后返回今天。unlockHour=0 时恒返回今天（次日零点即解冻）。</p>
     */
    static java.time.LocalDate unlockedThroughDate(java.time.LocalDate today, java.time.LocalTime now, int unlockHour) {
        int hour = Math.min(Math.max(unlockHour, 0), 23);
        return now.getHour() >= hour ? today : today.minusDays(1);
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
        MerchantWalletAccount account = merchantWalletService.findAccount(merchant.getMerchantId());
        long balance = account == null ? 0L : value(account.getBalanceCents());
        long frozen = account == null ? 0L : value(account.getFrozenCents());
        // CB-035：availableCents 与 V308 提现闸门同口径（扣 T+1 待结算），并把待结算透出给运营台
        long pendingSettle = account == null ? 0L : pendingSettleAmount(merchant.getMerchantId());
        // CB-086：仅余额为负时补账龄（负余额是少数，避免列表页对每行都查账本）
        MerchantWalletArrearsService.Arrears arrears =
                balance < 0 ? walletArrearsService.arrearsOf(merchant.getMerchantId()).orElse(null) : null;
        return new MerchantWalletAccountDto(
                merchant.getMerchantId(),
                merchant.getMerchantName(),
                merchant.getContactPhone(),
                merchant.getStatus(),
                balance,
                frozen,
                Math.max(0, balance - frozen - pendingSettle),
                pendingSettle,
                arrears == null || arrears.since() == null ? null : arrears.since().toString(),
                arrears == null || arrears.ageDays() < 0 ? null : arrears.ageDays()
        );
    }

    /**
     * CB-086：欠款名单 CSV（线下追缴用）。口径与 {@code walletArrearsService.negativeAccounts()} 同源：
     * **包含**账龄不可判的欠款人（ageDays 留空），按欠款金额降序。仅返回当前运营有范围的商户。
     * 前端以 blob 下载（管理端 token 在 sessionStorage，不能裸链）。
     */
    @Transactional(readOnly = true)
    public String arrearsCsv(Long operatorId) {
        permissionService.requireAnyPermission(operatorId,
                PERM_OPS_MERCHANT_WITHDRAW_LIST, PERM_OPS_FINANCE_VIEW, "ops:merchant:list");
        Set<String> allowed = merchantScopeService.allowedMerchantIds(operatorId);
        StringBuilder sb = new StringBuilder(csvHeader());
        if (allowed.isEmpty()) {
            return sb.toString();
        }
        // 处置提示与配置同源：阈值改了文案跟着变，不写死 90（否则报表会撒谎）
        int lockDays = walletArrearsService.lockThresholdDays();
        walletArrearsService.negativeAccounts().stream()
                .filter(a -> allowed.contains(a.merchantId()))
                .sorted(java.util.Comparator.comparingLong(MerchantWalletArrearsService.Arrears::arrearsCents).reversed())
                .forEach(a -> {
                    Merchant m = merchantMapper.findById(a.merchantId()).orElse(null);
                    String phone = m == null ? "" : firstNonBlank(m.getContactPhone(), m.getAlertContactPhone());
                    String hint;
                    if (a.ageDays() < 0) {
                        hint = "账龄不可判（无流水），人工核对";
                    } else if (lockDays > 0 && a.ageDays() >= lockDays) {
                        hint = "已满 " + lockDays + " 天（可停售；核销需商户非 ACTIVE）";
                    } else if (lockDays > 0) {
                        hint = "未满 " + lockDays + " 天";
                    } else {
                        hint = "锁柜已关闭（仅告警）";
                    }
                    // 统一走 CsvCells：CSV 公式注入中和 + RFC 转义（审计 P1-7 纪律）
                    sb.append(CsvCells.escape(a.merchantId())).append(',')
                            .append(CsvCells.escape(m == null ? "" : m.getMerchantName())).append(',')
                            .append(CsvCells.escape(phone)).append(',')
                            .append(CsvCells.escape(m == null ? "" : m.getStatus())).append(',')
                            .append(BigDecimal.valueOf(a.arrearsCents(), 2).toPlainString()).append(',')
                            .append(CsvCells.escape(a.since() == null ? "" : a.since().toString())).append(',')
                            .append(a.ageDays() < 0 ? "" : String.valueOf(a.ageDays())).append(',')
                            .append(CsvCells.escape(hint))
                            .append('\n');
                });
        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String csvHeader() {
        // BOM 让 Excel 按 UTF-8 打开中文表头
        return "\uFEFF商户编号,商户名称,联系电话,商户状态,欠款金额(元),欠款起算日,账龄(天),处置提示\n";
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
