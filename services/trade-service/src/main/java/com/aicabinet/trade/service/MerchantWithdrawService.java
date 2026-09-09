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
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Set;
import java.util.UUID;

@Service
public class MerchantWithdrawService {
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
                                   MerchantFeaturePackService merchantFeaturePackService,
                                   MerchantScopeService merchantScopeService,
                                   PermissionService permissionService,
                                   AdminAuditService auditService,
                                   DistributedLockService distributedLockService,
                                   ApprovalWorkflowService approvalWorkflowService,
                                   @Lazy MerchantWithdrawService self) {
        this.withdrawMapper = withdrawMapper;
        this.merchantMapper = merchantMapper;
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.merchantWalletService = merchantWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
        this.merchantFeaturePackService = merchantFeaturePackService;
        this.merchantScopeService = merchantScopeService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
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
            String refId = "ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String note = remark == null || remark.isBlank() ? "运营调账" : remark.trim();
            if (amountCents > 0) {
                merchantWalletService.credit(merchantId, amountCents, "ADJUST", "OPS_ADJUST", refId, note);
            } else {
                merchantWalletService.debit(merchantId, -amountCents, "ADJUST", "OPS_ADJUST", refId, note);
            }
            auditService.appendLog(operatorId, BIZ_WALLET_ADJUST, "MERCHANT_WALLET", merchantId,
                    "金额(分)=" + amountCents + "；备注=" + note);
            if (Math.abs(amountCents) >= properties.reviewThresholdCents()) {
                approvalWorkflowService.start(
                        BIZ_WALLET_ADJUST,
                        refId,
                        operatorId,
                        "商户调账 " + merchantId + " ¥" + String.format(Locale.ROOT, "%.2f", amountCents / 100.0));
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
        return createWithdraw(merchant, amountCents, requestNo, operatorId);
    }

    public MerchantWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        String merchantId = resolveMerchantId(userId);
        Merchant merchant = requireMerchant(merchantId);
        return createWithdraw(merchant, amountCents, requestNo, userId);
    }

    @Transactional(readOnly = true)
    public MerchantWalletOverviewDto merchantOverview(Long userId) {
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null || merchantIds.isEmpty()) {
            return new MerchantWalletOverviewDto(
                    false, null, null, null, null, null, List.of(), List.of());
        }
        String merchantId = merchantIds.stream().sorted().findFirst().orElse(null);
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
        validateAmount(merchant.getMerchantId(), amountCents);
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
                amountCents, properties.feeCents(), properties.feeBps()));
        request.setPayChannel(properties.mockEnabled() ? "MOCK" : "WECHAT");
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= properties.reviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
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
        merchantWalletService.freezeForWithdraw(merchant.getMerchantId(), amountCents,
                WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
        return PayoutGate.needPayout(toDto(request));
    }

    /**
     * 短事务标 PAYING → 渠道打款（事务外）→ 短事务落 PAID/FAILED。
     */
    public MerchantWithdrawRequestDto executePayout(long requestId) {
        MerchantWithdrawRequest paying = self.markPaying(requestId);
        Merchant merchant = requireMerchant(paying.getMerchantId());
        MerchantWithdrawPayoutService.PayoutResult result = payoutService.payout(paying, merchant);
        return self.finalizePayout(requestId, result);
    }

    @Transactional
    public MerchantWithdrawRequest markPaying(long requestId) {
        MerchantWithdrawRequest request = requireRequest(requestId);
        if (!Set.of(STATUS_APPROVED, "FAILED", "PAYING").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        request.setStatus("PAYING");
        request.setUpdatedAt(Instant.now());
        withdrawMapper.updateById(request);
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
            merchantWalletService.consumeFrozen(request.getMerchantId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款成功");
            return toDto(request);
        }
        request.setStatus("FAILED");
        withdrawMapper.updateById(request);
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

    private void validateAmount(String merchantId, long amountCents) {
        if (amountCents < properties.minAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "最低提现 " + (properties.minAmountCents() / 100.0) + " 元");
        }
        MerchantWalletAccount account = merchantWalletService.ensureAccount(merchantId);
        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());
        if (available < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");
        }
        Instant start = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long used = withdrawMapper.sumAmountByMerchantSince(merchantId, start);
        if (used + amountCents > properties.dailyLimitCents()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "超过单日提现限额");
        }
    }

    private String resolveMerchantId(Long userId) {
        Set<String> merchantIds = merchantFeaturePackService.allowedMerchantIdsForPack(
                userId, MerchantFeaturePacks.BIZ);
        if (merchantIds == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "运营账号请走后台调账/代提现");
        }
        if (merchantIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未绑定商户");
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
                request.getFeeCents() == null ? 0L : request.getFeeCents()
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
        return "MW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
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
