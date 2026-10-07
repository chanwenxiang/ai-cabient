package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineWalletOverviewDto;
import com.aicabinet.common.dto.LineWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelLimits;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import com.aicabinet.trade.util.BizIds;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class LineWithdrawService {
    private static final Logger log = LoggerFactory.getLogger(LineWithdrawService.class);

    private static final String PERM_OPS_LINE_WITHDRAW_REVIEW = "ops:line-withdraw:review";
    private static final String LINE_WITHDRAW_REVIEW = "LINE_WITHDRAW_REVIEW";
    private static final String WITHDRAW = "WITHDRAW";
    private static final String STATUS_APPROVED = "APPROVED";


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final WithdrawEligibilityService withdrawEligibilityService;
    private final LineWithdrawRequestMapper withdrawMapper;
    private final LineManagerMapper managerMapper;
    private final LineDeviceMapper deviceMapper;
    private final LineManagerService lineManagerService;
    private final LineWalletService lineWalletService;
    private final LineWithdrawPayoutService payoutService;
    private final LineWithdrawProperties properties;
    /** V307：限额/费率解析（运营台配置优先于 yml，运行期生效）。 */
    private final WithdrawPolicyResolver policy;
    /** V308：收款账户管理（申请时锁定收款方）。 */
    private final PayoutAccountService payoutAccountService;
    /** V308：按申请时快照取回真实账户（出款要解密密文，快照里只有掩码）。 */
    private final PayoutAccountMapper payoutAccountMapper;
    /** V308：渠道硬限额（单笔 / 单收款人单日 / 单通道当日总额）。 */
    private final PayoutChannelRegistry payoutChannelRegistry;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final LineWithdrawService self;

    private static final String BIZ_LINE_WITHDRAW = "LINE_WITHDRAW";
    public LineWithdrawService(LineWithdrawRequestMapper withdrawMapper,
                               LineManagerMapper managerMapper,
                               LineDeviceMapper deviceMapper,
                               LineManagerService lineManagerService,
                               LineWalletService lineWalletService,
                               LineWithdrawPayoutService payoutService,
                               LineWithdrawProperties properties,
                               PermissionService permissionService,
                               AdminAuditService auditService,
                               DistributedLockService distributedLockService,
                               ApprovalWorkflowService approvalWorkflowService,
                               WithdrawPolicyResolver policy,
                               PayoutAccountService payoutAccountService,
                               PayoutAccountMapper payoutAccountMapper,
                               PayoutChannelRegistry payoutChannelRegistry,
                               WithdrawEligibilityService withdrawEligibilityService,
                               @Lazy LineWithdrawService self) {
        this.withdrawEligibilityService = withdrawEligibilityService;
        this.withdrawMapper = withdrawMapper;
        this.managerMapper = managerMapper;
        this.deviceMapper = deviceMapper;
        this.lineManagerService = lineManagerService;
        this.lineWalletService = lineWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
        this.policy = policy;
        this.payoutAccountService = payoutAccountService;
        this.payoutAccountMapper = payoutAccountMapper;
        this.payoutChannelRegistry = payoutChannelRegistry;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public PageResult<LineWithdrawRequestDto> list(Long operatorId, String status, Long managerId, int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                "ops:line-manager:list", PERM_OPS_LINE_WITHDRAW_REVIEW, "ops:finance:view");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<LineWithdrawRequest> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            q.eq(LineWithdrawRequest::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (managerId != null) {
            q.eq(LineWithdrawRequest::getManagerId, managerId);
        }
        q.orderByDesc(LineWithdrawRequest::getCreatedAt);
        Page<LineWithdrawRequest> result = withdrawMapper.selectPage(new Page<>(p + 1L, s), q);
        List<LineWithdrawRequestDto> items = result.getRecords().stream().map(this::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> payoutMode(Long operatorId) {
        permissionService.requireAnyPermission(operatorId,
                "ops:line-manager:list", PERM_OPS_LINE_WITHDRAW_REVIEW, "ops:finance:view");
        return payoutService.modeInfo();
    }

    public LineWithdrawRequestDto apply(long managerId, long amountCents, String requestNo) {
        LineManager manager = lineManagerService.requireManager(managerId);
        // 🔴 V321 线长实名门禁。必须在 createWithdraw 之前 ——
        //    createWithdraw 会开钱包锁并可能直接打款。
        //    校验依据是 manager.user_id（线长绑定的用户），不是 managerId 本身。
        withdrawEligibilityService.requireLineWithdrawEligible(manager.getUserId());
        return createWithdraw(manager, amountCents, requestNo, null);
    }

    public LineWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        LineManager manager = lineManagerService.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未绑定线长身份"));
        if (!LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "线长账号不可用");
        }
        // 🔴 V321：走 userId 找 线长时 userId 必然非空，但保留门禁以防将来
        //    出现「线长记录存在、user_id 为空」的数据（当前 apply 路径已能拦）。
        withdrawEligibilityService.requireLineWithdrawEligible(userId);
        return createWithdraw(manager, amountCents, requestNo, userId);
    }

    public LineWithdrawRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_WITHDRAW_REVIEW);
        LineWithdrawRequest request = requireRequest(requestId);
        return runWithLineWalletLock(request.getManagerId(), () -> {
            PayoutGate gate = self.completeReview(operatorId, requestId, approve, remark);
            if (gate.shouldPayout()) {
                return self.executePayout(gate.requestId());
            }
            return gate.dto();
        });
    }

    @Transactional
    public PayoutGate completeReview(Long operatorId, long requestId, boolean approve, String remark) {
        LineWithdrawRequest request = requireRequest(requestId);
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
                    operatorId, BIZ_LINE_WITHDRAW, String.valueOf(request.getRequestId()), trim(remark));
            request.setStatus("REJECTED");
            withdrawMapper.updateById(request);
            lineWalletService.releaseFrozen(request.getManagerId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现驳回释放");
            auditService.appendLog(operatorId, LINE_WITHDRAW_REVIEW, BIZ_LINE_WITHDRAW,
                    String.valueOf(request.getRequestId()), "驳回；金额(分)=" + request.getAmountCents()
                            + "；备注=" + trim(remark));
            return PayoutGate.done(toDto(request));
        }
        approvalWorkflowService.completeApproved(
                operatorId, BIZ_LINE_WITHDRAW, String.valueOf(request.getRequestId()), trim(remark));
        if (!approvalWorkflowService.isInstanceApproved(
                BIZ_LINE_WITHDRAW, String.valueOf(request.getRequestId()))) {
            auditService.appendLog(operatorId, LINE_WITHDRAW_REVIEW, BIZ_LINE_WITHDRAW,
                    String.valueOf(request.getRequestId()), "初审通过；金额(分)=" + request.getAmountCents());
            return PayoutGate.done(toDto(request));
        }
        request.setStatus(STATUS_APPROVED);
        withdrawMapper.updateById(request);
        auditService.appendLog(operatorId, LINE_WITHDRAW_REVIEW, BIZ_LINE_WITHDRAW,
                String.valueOf(request.getRequestId()), "通过；金额(分)=" + request.getAmountCents()
                        + "；备注=" + trim(remark));
        return PayoutGate.needPayout(toDto(request));
    }

    public LineWithdrawRequestDto payout(Long operatorId, long requestId) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_WITHDRAW_REVIEW);
        LineWithdrawRequest request = requireRequest(requestId);
        return runWithLineWalletLock(request.getManagerId(), () -> {
            if (!Set.of(STATUS_APPROVED, "FAILED").contains(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
            }
            auditService.appendLog(operatorId, "LINE_WITHDRAW_PAYOUT", BIZ_LINE_WITHDRAW,
                    String.valueOf(requestId), "打款金额(分)=" + request.getAmountCents());
            return self.executePayout(requestId);
        });
    }

    /** 打款失败后取消：解冻资金并标记 CANCELLED。 */
    @Transactional
    public LineWithdrawRequestDto cancelFailed(Long operatorId, long requestId, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_WITHDRAW_REVIEW);
        LineWithdrawRequest request = requireRequest(requestId);
        return runWithLineWalletLock(request.getManagerId(), () -> {
            if (!"FAILED".equals(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "仅打款失败的提现单可取消解冻");
            }
            Instant now = Instant.now();
            String note = trim(remark);
            request.setStatus("CANCELLED");
            request.setReviewRemark(note == null || note.isBlank() ? "打款失败取消解冻" : note);
            request.setUpdatedAt(now);
            withdrawMapper.updateById(request);
            lineWalletService.releaseFrozen(request.getManagerId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现失败取消解冻");
            auditService.appendLog(operatorId, "LINE_WITHDRAW_CANCEL", BIZ_LINE_WITHDRAW,
                    String.valueOf(requestId), "取消解冻；金额(分)=" + request.getAmountCents());
            return toDto(request);
        });
    }

    @Transactional(readOnly = true)
    public LineWalletOverviewDto merchantOverview(Long userId) {
        return lineManagerService.findByUserId(userId)
                .map(manager -> {
                    LineWalletAccount account = lineWalletService.findAccount(manager.getManagerId());
                    long balance = value(account == null ? null : account.getBalanceCents());
                    long frozen = value(account == null ? null : account.getFrozenCents());
                    return new LineWalletOverviewDto(
                            true,
                            manager.getManagerId(),
                            manager.getManagerName(),
                            manager.getPhone(),
                            balance,
                            frozen,
                            balance - frozen,
                            lineManagerService.ledgersForManager(manager.getManagerId(), 10),
                            withdrawMapper.findByManagerIdOrderByCreatedAtDesc(manager.getManagerId(), 10).stream()
                                    .map(this::toDto)
                                    .toList()
                    );
                })
                .orElseGet(() -> new LineWalletOverviewDto(
                        false, null, null, null, null, null, null, List.of(), List.of()));
    }

    private LineWithdrawRequestDto createWithdraw(LineManager manager, long amountCents, String requestNo,
                                                  Long submitterUserId) {
        return runWithLineWalletLock(manager.getManagerId(), () -> {
            PayoutGate gate = self.persistWithdrawApplication(manager, amountCents, requestNo, submitterUserId);
            if (gate.shouldPayout()) {
                return self.executePayout(gate.requestId());
            }
            return gate.dto();
        });
    }

    @Transactional
    public PayoutGate persistWithdrawApplication(LineManager manager, long amountCents, String requestNo,
                                                 Long submitterUserId) {
        // V308：申请时锁定收款账户（不指定则用默认）—— 快照后历史不可变。
        // 🔴 必须在 validateAmount 之前：渠道限额校验依赖账户的 channel 与 accountId
        //    （单收款人单日 / 单通道当日总额两个维度都要用到）。
        PayoutAccount payeeAccount = payoutAccountService.resolveForApply(
                PayoutConstants.PAYEE_OWNER_LINE_MANAGER, String.valueOf(manager.getManagerId()), null);
        validateAmount(manager.getManagerId(), amountCents, payeeAccount);
        String no = normalizeRequestNo(requestNo);
        var existing = withdrawMapper.findByRequestNo(no);
        if (existing.isPresent()) {
            return PayoutGate.done(toDto(existing.get()));
        }
        Instant now = Instant.now();
        LineWithdrawRequest request = new LineWithdrawRequest();
        request.setRequestNo(no);
        request.setManagerId(manager.getManagerId());
        request.setAmountCents(amountCents);
        request.setFeeCents(WithdrawFeeCalculator.computeFeeCents(
                amountCents, policy.lineFeeCents(), policy.lineFeeBps(), policy.lineFeeCapCents()));
        // V308：渠道取自「所选收款账户」，不再由 mock 开关的字面量决定 —— 线长可按账户走不同通道
        request.setPayChannel(payeeAccount.getChannel());
        applyPayeeSnapshot(request, payeeAccount);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= policy.lineReviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
            applyIdemKey(request);
            lineWalletService.freezeForWithdraw(manager.getManagerId(), amountCents,
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
            approvalWorkflowService.start(
                    BIZ_LINE_WITHDRAW,
                    String.valueOf(request.getRequestId()),
                    submitterUserId,
                    "线长提现 " + request.getRequestNo() + " ¥"
                            + String.format(Locale.ROOT, "%.2f", amountCents / 100.0));
            return PayoutGate.done(toDto(request));
        }
        request.setStatus(STATUS_APPROVED);
        request.setReviewRemark("低于审核阈值自动通过");
        request.setReviewedAt(now);
        withdrawMapper.insert(request);
        applyIdemKey(request);
        lineWalletService.freezeForWithdraw(manager.getManagerId(), amountCents,
                WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
        return PayoutGate.needPayout(toDto(request));
    }

    /**
     * 写打款幂等键。<b>必须在 insert 之后</b> —— 键里含 requestId（自增主键）。
     *
     * <p>形态：{@code LW:<requestId>:<随机>}。随机段让「同一 requestId 的不同重试」
     * 保持同一个键（重试不换键⇒ 渠道侧幂等），而不同单据天然不同。
     */
    private void applyIdemKey(LineWithdrawRequest request) {
        request.setIdemKey(PayoutAccountService.newIdemKey(
                LineWithdrawPayoutService.IDEM_PREFIX, request.getRequestId()));
        withdrawMapper.updateById(request);
    }

    /**
     * 把收款账户信息<b>快照</b>进提现单。只写掩码，<b>永不写明文</b>：
     * 快照列可能被列表接口直接返回。
     */
    private void applyPayeeSnapshot(LineWithdrawRequest request, PayoutAccount account) {
        request.setPayoutAccountId(account.getAccountId());
        request.setPayeeAccountType(account.getAccountType());
        request.setPayeeAccountName(account.getAccountName());
        request.setPayeeAccountNoMask(account.getAccountNoMask());
        request.setPayeeBankName(account.getBankName());
        request.setPayeeTaxNo(account.getTaxNo());
    }

    public LineWithdrawRequestDto executePayout(long requestId) {
        LineWithdrawRequest paying = self.markPaying(requestId);
        LineManager manager = lineManagerService.requireManager(paying.getManagerId());
        PayoutAccount account = resolveSnapshotAccount(paying);
        LineWithdrawPayoutService.PayoutResult result = payoutService.payout(paying, manager, account);
        return self.finalizePayout(requestId, result);
    }

    /**
     * V308：取本次提现单的收款账户快照源。
     *
     * <p>优先级：① 单据上的 {@code payout_account_id}（申请时用的那个，最准确）
     * → ② 主体默认账户（V308 前的存量单无 id 时的兜底）。
     *
     * <p>⚠️ <b>不按快照内容出款</b>：快照的 {@code payee_*} 列只用于展示与对账，
     * 真实出款必须解密账户密文（快照里没有明文）。
     */
    private PayoutAccount resolveSnapshotAccount(LineWithdrawRequest request) {
        String ownerId = String.valueOf(request.getManagerId());
        if (request.getPayoutAccountId() != null) {
            Optional<PayoutAccount> snapshot =
                    payoutAccountMapper.findByAccountId(request.getPayoutAccountId());
            if (snapshot.isPresent()) {
                return snapshot.get();
            }
            // 🔴 走到这里说明「申请时用的账户」被物理删除了（只停用不会）。
            //    回落默认账户 + 留warn：宁可打款到当前默认账户，也不要整笔卡在 PAYING。
            log.warn("line withdraw snapshot account missing, fallback to default: requestId={}, accountId={}",
                    request.getRequestId(), request.getPayoutAccountId());
        }
        return payoutAccountService.resolveForApply(
                PayoutConstants.PAYEE_OWNER_LINE_MANAGER, ownerId, null);
    }

    @Transactional
    public LineWithdrawRequest markPaying(long requestId) {
        LineWithdrawRequest request = requireRequest(requestId);
        String previousStatus = request.getStatus();
        if (!Set.of(STATUS_APPROVED, "FAILED", "PAYING").contains(previousStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        request.setStatus("PAYING");
        request.setUpdatedAt(Instant.now());
        withdrawMapper.updateById(request);
        if ("FAILED".equals(previousStatus)) {
            // FAILED 落账时已释放冻结；重试打款前需重新冻结，保证 PAID consumeFrozen 口径
            lineWalletService.freezeForWithdraw(request.getManagerId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "重试打款重新冻结");
        }
        return request;
    }

    @Transactional
    public LineWithdrawRequestDto finalizePayout(long requestId, LineWithdrawPayoutService.PayoutResult result) {
        LineWithdrawRequest request = requireRequest(requestId);
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
            lineWalletService.consumeFrozenSplit(
                    request.getManagerId(),
                    WithdrawFeeCalculator.netPayoutCents(request.getAmountCents(), feeCents),
                    feeCents,
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款成功");
            return toDto(request);
        }
        request.setStatus("FAILED");
        withdrawMapper.updateById(request);
        // 打款失败即释放冻结，避免冻结悬挂（与人工 cancelFailed 解冻口径一致）
        lineWalletService.releaseFrozen(request.getManagerId(), request.getAmountCents(),
                WITHDRAW, String.valueOf(request.getRequestId()), "提现打款失败释放");
        return toDto(request);
    }

    public record PayoutGate(LineWithdrawRequestDto dto, boolean shouldPayout, long requestId) {
        static PayoutGate done(LineWithdrawRequestDto dto) {
            return new PayoutGate(dto, false, dto.requestId() == null ? 0L : dto.requestId());
        }

        static PayoutGate needPayout(LineWithdrawRequestDto dto) {
            return new PayoutGate(dto, true, dto.requestId());
        }
    }

    /** 打款卡 PAYING 的超时阈值：超过即由对账调度兜底处置（H38；真实渠道转人工，MOCK 自动失败）。 */
    static final long PAYING_TIMEOUT_MINUTES = 60;

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
        List<LineWithdrawRequest> stale =
                withdrawMapper.findByStatusAndUpdatedAtBefore("PAYING", cutoff);
        int failed = 0;
        for (LineWithdrawRequest staleRequest : stale) {
            try {
                if (failSingleStalePaying(staleRequest.getRequestId())) {
                    failed++;
                }
            } catch (Exception e) {
                log.warn("stale PAYING line withdraw sweep failed requestId={} err={}",
                        staleRequest.getRequestId(), e.toString());
            }
        }
        return failed;
    }

    private boolean failSingleStalePaying(long requestId) {
        return runWithLineWalletLock(requireRequest(requestId).getManagerId(), () -> {
            LineWithdrawRequest request = requireRequest(requestId);
            if (!"PAYING".equals(request.getStatus())) {
                return false;
            }
            // F3 判据单点化（P3-1b）：真实渠道回执丢失时自动置 FAILED = 双重支出，禁止
            if (!WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout(request.getPayChannel())) {
                log.warn("stale PAYING line withdraw on real channel left for manual reconciliation requestId={} channel={}",
                        requestId, request.getPayChannel());
                auditService.appendLog(0L, "LINE_WITHDRAW_PAYOUT_STALE_MANUAL", BIZ_LINE_WITHDRAW,
                        String.valueOf(requestId),
                        WithdrawPayoutPolicy.payingTimeoutManualNote(request.getPayChannel(), request.getAmountCents()));
                return false;
            }
            request.setStatus("FAILED");
            request.setPayoutMessage(WithdrawPayoutPolicy.payingTimeoutFailMessage(PAYING_TIMEOUT_MINUTES));
            request.setUpdatedAt(Instant.now());
            withdrawMapper.updateById(request);
            lineWalletService.releaseFrozen(request.getManagerId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款超时释放");
            auditService.appendLog(0L, "LINE_WITHDRAW_PAYOUT_TIMEOUT", BIZ_LINE_WITHDRAW,
                    String.valueOf(requestId),
                    WithdrawPayoutPolicy.payingTimeoutAutoNote(request.getAmountCents()));
            return true;
        });
    }

    private void validateAmount(long managerId, long amountCents, PayoutAccount payeeAccount) {
        if (amountCents < policy.lineMinAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "最低提现 " + (policy.lineMinAmountCents() / 100.0) + " 元");
        }
        // V307 补单笔上限：原先线长侧只有下限+单日，运营手工输大额无代码层拦截
        if (policy.lineMaxAmountCents() > 0 && amountCents > policy.lineMaxAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单笔提现上限 " + (policy.lineMaxAmountCents() / 100.0) + " 元");
        }
        long activeDevices = deviceMapper.selectCount(Wrappers.<LineDevice>lambdaQuery()
                .eq(LineDevice::getManagerId, managerId)
                .and(w -> w.isNull(LineDevice::getStatus)
                        .or()
                        .eq(LineDevice::getStatus, "ACTIVE")
                        .or()
                        .eq(LineDevice::getStatus, "BOUND")));
        if (activeDevices <= 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "未绑定柜机，禁止提现（请先完成地推柜机绑定）");
        }
        LineWalletAccount account = lineWalletService.ensureAccount(managerId);
        long available = value(account.getBalanceCents()) - value(account.getFrozenCents());
        if (available < amountCents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "可用余额不足");
        }
        Instant start = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long used = withdrawMapper.sumAmountByManagerSince(managerId, start);
        if (used + amountCents > policy.lineDailyLimitCents()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "超过单日提现限额");
        }
        validateChannelLimits(payeeAccount, amountCents, start);
    }

    /**
     * V308：渠道维度限额校验，语义与商户侧完全一致（单笔 / 单收款人单日 / 单通道当日总额）。
     *
     * <p>🔴 <b>为什么线长侧也要有</b>：线长默认渠道是微信，而微信单笔默认上限只有 ¥200
     * （{@code WeChatPayoutChannel.channelLimits()}）。若不校验，运营给线长批 ¥500 的提现
     * 会在渠道侧被拒 ⇒ 单子卡PAYING 转人工。提前拦才能给出「单笔不能超 ¥200」的明确提示。
     *
     * <p>⚠️ <b>「单通道当日总额」是全平台共享池</b>：微信那个 5 万/日限制的是
     * <b>整个商户号</b>，商户提现与线长提现<b>共用</b>同一个池子。
     * 因此这里查的是该通道<b>所有主体</b>的当日累计（商户单+ 线长单都算）。
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
                policy.lineMaxAmountCents(), 0L, policy.lineDailyLimitCents());
        PayoutChannelLimits limits = channel.channelLimits().effective(opsLimits);

        if (limits.hasSingleLimit() && amountCents > limits.singleCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    channel.channel() + " 单笔提现上限 " + yuan(limits.singleCents())
                            + " 元（渠道额度限制，超出请分次提现或改用银行通道）");
        }
        if (limits.hasPerPayeeDailyLimit() && payeeAccount.getAccountId() != null) {
            long payeeUsed = payoutAccountService.sumPaidAmountByAccountSince(
                    payeeAccount.getAccountId(), dayStart);
            if (payeeUsed + amountCents > limits.perPayeeDailyCents()) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "该收款账户今日已提现 " + yuan(payeeUsed) + " 元，"
                                + channel.channel() + " 单收款人单日上限 " + yuan(limits.perPayeeDailyCents())
                                + " 元（超出请明日再试或改用银行通道）");
            }
        }
        if (limits.hasDailyTotalLimit()) {
            long channelUsed = payoutAccountService.sumPaidAmountByChannelSince(
                    payeeAccount.getChannel(), dayStart);
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

    private LineWithdrawRequest requireRequest(long requestId) {
        return withdrawMapper.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "提现单不存在"));
    }

    private LineWithdrawRequestDto toDto(LineWithdrawRequest request) {
        LineManager manager = managerMapper.findById(request.getManagerId()).orElse(null);
        return new LineWithdrawRequestDto(
                request.getRequestId(),
                request.getRequestNo(),
                request.getManagerId(),
                manager == null ? null : manager.getManagerName(),
                manager == null ? null : manager.getPhone(),
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
                // V308 收款方快照（payeeAccountNoMask 本身已是掩码，直接透传；永不返回明文）
                request.getPayoutAccountId(),
                request.getPayeeAccountType(),
                request.getPayeeAccountName(),
                request.getPayeeAccountNoMask(),
                request.getPayeeBankName(),
                request.getIdemKey(),
                request.getChannelOrderNo()
        );
    }

    private static String normalizeRequestNo(String requestNo) {
        if (requestNo != null && !requestNo.isBlank()) {
            return requestNo.trim();
        }
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

    static String lineWalletLockKey(long managerId) {
        return "line:wallet:" + managerId;
    }

    private <T> T runWithLineWalletLock(long managerId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(lineWalletLockKey(managerId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "钱包处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lineWalletLockKey(managerId));
        }
    }
}
