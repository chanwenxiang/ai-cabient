package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.BalanceRefundRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.BalanceRefundAllocation;
import com.aicabinet.trade.domain.BalanceRefundRequest;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.BalanceRefundAllocationMapper;
import com.aicabinet.trade.mapper.BalanceRefundRequestMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.support.ApiMessages;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BalanceRefundService {

    private static final Logger log = LoggerFactory.getLogger(BalanceRefundService.class);
    private static final String STATUS_PENDING = "PENDING_REVIEW";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    private static final String STATUS_FAILED = "FAILED";
    /** H63: 渠道切片部分成功时保留进度（已成功切片不回滚），复审可续退剩余部分。 */
    private static final String STATUS_PARTIAL = "PARTIAL";

    private final BalanceRefundRequestMapper requestMapper;
    private final BalanceRefundAllocationMapper allocationMapper;
    private final UserAccountMapper accountMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final PaymentService paymentService;
    private final BalanceLedgerService balanceLedgerService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final SystemConfigService systemConfigService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效（C05 分阶段事务）。 */
    private final BalanceRefundService self;

    private static final String BIZ_BALANCE_REFUND = "BALANCE_REFUND";

    public BalanceRefundService(BalanceRefundRequestMapper requestMapper,
                                BalanceRefundAllocationMapper allocationMapper,
                                UserAccountMapper accountMapper,
                                RechargeOrderMapper rechargeOrderMapper,
                                PaymentService paymentService,
                                BalanceLedgerService balanceLedgerService,
                                PermissionService permissionService,
                                AdminAuditService auditService,
                                DistributedLockService distributedLockService,
                                ApprovalWorkflowService approvalWorkflowService,
                                SystemConfigService systemConfigService,
                                @Lazy BalanceRefundService self) {
        this.requestMapper = requestMapper;
        this.allocationMapper = allocationMapper;
        this.accountMapper = accountMapper;
        this.rechargeOrderMapper = rechargeOrderMapper;
        this.paymentService = paymentService;
        this.balanceLedgerService = balanceLedgerService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.systemConfigService = systemConfigService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<BalanceRefundRequestDto> listMine(Long userId) {
        return requestMapper.findByUserIdOrderByCreatedAtDesc(userId, 20).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public BalanceRefundRequestDto apply(Long userId, int amountCents, String reason) {
        if (amountCents < 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额至少 ¥1.00");
        }
        int maxCents = systemConfigService.getInt(SystemConfigService.BALANCE_REFUND_MAX_CENTS, 500_000);
        if (maxCents > 0 && amountCents > maxCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单次申请不超过 ¥" + String.format("%.2f", maxCents / 100.0));
        }
        return runWithBalanceRefundLock(userId, () -> doApply(userId, amountCents, reason));
    }

    private BalanceRefundRequestDto doApply(Long userId, int amountCents, String reason) {
        if (requestMapper.countByUserIdAndStatus(userId, STATUS_PENDING) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有待审核的退款申请，请等待处理完成");
        }
        UserAccount account = accountMapper.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        int available = Math.max(0, account.getBalanceCents() - Math.max(0, account.getFrozenCents()));
        if (amountCents > available) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "可用余额不足（可用 ¥" + String.format("%.2f", available / 100.0) + "）");
        }
        int refundableChannel = sumRefundableChannelCents(userId);
        if (amountCents > refundableChannel) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "可原路退回的充值不足（最多 ¥" + String.format("%.2f", refundableChannel / 100.0)
                            + "）。运营赠送或已消费对应充值的部分无法原路退微信，请联系客服。");
        }

        account.setFrozenCents(Math.max(0, account.getFrozenCents()) + amountCents);
        accountMapper.save(account);
        balanceLedgerService.recordFreezeOnly(userId, new BalanceLedgerService.BalanceFreezeCommand(
                amountCents, "BALANCE_REFUND_FREEZE", null,
                "BALANCE_REFUND_FREEZE:" + userId + ":" + Instant.now().toEpochMilli(),
                "余额退款申请冻结", account.getBalanceCents(), account.getBalanceCents()));

        Instant now = Instant.now();
        BalanceRefundRequest req = new BalanceRefundRequest();
        req.setRequestNo("BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        req.setUserId(userId);
        req.setAmountCents(amountCents);
        req.setStatus(STATUS_PENDING);
        req.setReason(trim(reason));
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        requestMapper.insert(req);
        approvalWorkflowService.start(
                BIZ_BALANCE_REFUND,
                String.valueOf(req.getRequestId()),
                userId,
                "余额退款 " + req.getRequestNo() + " ¥"
                        + String.format(Locale.ROOT, "%.2f", amountCents / 100.0));
        log.info("balance refund applied user={} amount={} request={}", userId, amountCents, req.getRequestNo());
        return toDto(req);
    }

    @Transactional(readOnly = true)
    public PageResult<BalanceRefundRequestDto> listAdmin(Long operatorId, String status, Long userId,
                                                         int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                "ops:balance-refund:list", "ops:balance-refund:review", "ops:finance:view");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        LambdaQueryWrapper<BalanceRefundRequest> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            q.eq(BalanceRefundRequest::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (userId != null && userId > 0) {
            q.eq(BalanceRefundRequest::getUserId, userId);
        }
        q.orderByDesc(BalanceRefundRequest::getCreatedAt);
        Page<BalanceRefundRequest> result = requestMapper.selectPage(new Page<>(p + 1L, s), q);
        return new PageResult<>(result.getRecords().stream().map(this::toDto).toList(),
                p, s, result.getTotal());
    }

    /**
     * 审核。无外层长事务：拒绝/审批门/资金各为独立短事务，渠道 HTTP 在事务外（C05）。
     * 锁外仅做存在性预查；进入锁后一律以 selectByIdForUpdate 重查行再做状态守卫（C20）。
     */
    public BalanceRefundRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, "ops:balance-refund:review");
        BalanceRefundRequest snapshot = requestMapper.selectById(requestId);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在");
        }
        return runWithBalanceRefundLock(snapshot.getUserId(),
                () -> doReview(operatorId, snapshot, approve, remark));
    }

    private BalanceRefundRequestDto doReview(Long operatorId, BalanceRefundRequest snapshot,
                                             boolean approve, String remark) {
        if (!approve) {
            if (STATUS_PARTIAL.equals(snapshot.getStatus())) {
                // H63: 已有渠道成功切片，驳回会与渠道资金不一致，必须走续退
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "部分成功的退款申请不可驳回，请复审续退剩余切片");
            }
            return self.rejectRefund(operatorId, snapshot.getRequestId(), remark);
        }
        if (STATUS_PARTIAL.equals(snapshot.getStatus())) {
            // H63: 复审续退 —— 审批流此前已通过，直接续跑未成功切片（按本地记录跳过已成功切片）
            return toDto(self.resumePartialRefund(operatorId, snapshot.getRequestId()));
        }
        ApprovalGate gate = self.completeApprovalGate(operatorId, snapshot.getRequestId(), remark);
        if (!gate.fullyApproved()) {
            return toDto(gate.req());
        }
        return toDto(executeApprovedRefund(gate.req()));
    }

    /** 审批门（短事务）：行锁重查 + PENDING 守卫 + 工作流审批完成。 */
    public record ApprovalGate(BalanceRefundRequest req, boolean fullyApproved) {}

    @Transactional
    public ApprovalGate completeApprovalGate(Long operatorId, long requestId, String remark) {
        BalanceRefundRequest req = requirePendingForUpdate(requestId);
        req.setReviewerId(operatorId);
        req.setReviewRemark(trim(remark));
        req.setReviewedAt(Instant.now());
        req.setUpdatedAt(Instant.now());
        approvalWorkflowService.completeApproved(
                operatorId, BIZ_BALANCE_REFUND, String.valueOf(req.getRequestId()), trim(remark));
        boolean fullyApproved = approvalWorkflowService.isInstanceApproved(
                BIZ_BALANCE_REFUND, String.valueOf(req.getRequestId()));
        if (!fullyApproved) {
            requestMapper.updateById(req);
            auditService.appendLog(operatorId, "BALANCE_REFUND_APPROVE", BIZ_BALANCE_REFUND,
                    String.valueOf(req.getRequestId()), "初审通过 " + req.getRequestNo());
        }
        return new ApprovalGate(req, fullyApproved);
    }

    /** 驳回（短事务）：行锁重查 + PENDING 守卫 + 释放冻结 + 置 REJECTED。 */
    @Transactional
    public BalanceRefundRequestDto rejectRefund(Long operatorId, long requestId, String remark) {
        BalanceRefundRequest req = requirePendingForUpdate(requestId);
        req.setReviewerId(operatorId);
        req.setReviewRemark(trim(remark));
        req.setReviewedAt(Instant.now());
        req.setUpdatedAt(Instant.now());
        approvalWorkflowService.completeRejected(
                operatorId, BIZ_BALANCE_REFUND, String.valueOf(req.getRequestId()), trim(remark));
        releaseFreeze(req.getUserId(), req.getAmountCents(), req.getRequestNo());
        req.setStatus(STATUS_REJECTED);
        requestMapper.updateById(req);
        auditService.appendLog(operatorId, "BALANCE_REFUND_REJECT", BIZ_BALANCE_REFUND,
                String.valueOf(req.getRequestId()), "驳回 " + req.getRequestNo());
        return toDto(req);
    }

    private BalanceRefundRequest requirePendingForUpdate(long requestId) {
        BalanceRefundRequest req = requestMapper.selectByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在"));
        if (!STATUS_PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可审核");
        }
        return req;
    }

    /**
     * 已批准的原路退款（C05 三段式，任何时刻不出现「渠道成功但本地无账」）：
     * <ol>
     *   <li>事务①：规划切片（稳定 outRefundNo）→ 扣减用户余额 → 落切片与 PROCESSING 状态并提交；</li>
     *   <li>事务外：按切片调渠道退款（稳定单号幂等，重试安全）；</li>
     *   <li>事务②：全部成功 → REFUNDED；任一失败 → 未成功切片扣减冲回并置 FAILED。</li>
     * </ol>
     */
    private BalanceRefundRequest executeApprovedRefund(BalanceRefundRequest req) {
        List<BalanceRefundAllocation> allocations = self.debitAndMarkProcessing(req.getRequestId());
        return runChannelSlices(req, allocations, List.of());
    }

    /**
     * H63: PARTIAL 复审续退 —— 只规划未成功切片（跳过本地已成功记录的充值单），
     * 余额仅补扣未成功部分，随后复用三段式（PROCESSING → 渠道 → REFUNDED/PARTIAL）。
     */
    public BalanceRefundRequest resumePartialRefund(Long operatorId, long requestId) {
        // 先取已完成切片快照（续退事务只会追加新切片，不会改动既有行）
        List<BalanceRefundAllocation> doneBefore = allocationMapper.findByRequestId(requestId);
        List<BalanceRefundAllocation> resumed = self.debitRemainingAndMarkProcessing(requestId);
        BalanceRefundRequest req = requestMapper.selectById(requestId);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在");
        }
        if (resumed.isEmpty()) {
            // 无剩余切片（如渠道已全部成功但状态未跟上）：直接置 REFUNDED
            return self.markRefundSuccess(requestId);
        }
        return runChannelSlices(req, resumed, doneBefore);
    }

    /**
     * 渠道切片执行循环（逐片 HTTP，事务外；稳定退款号保证重试幂等）。
     * @param doneBefore 此前批次已成功切片（H63 续退时参与失败冲回计算）
     */
    private BalanceRefundRequest runChannelSlices(BalanceRefundRequest req,
                                                  List<BalanceRefundAllocation> allocations,
                                                  List<BalanceRefundAllocation> doneBefore) {
        List<BalanceRefundAllocation> succeeded = new ArrayList<>();
        RuntimeException failure = null;
        for (BalanceRefundAllocation alloc : allocations) {
            try {
                paymentService.refundRechargeChannelPartial(
                        alloc.getRechargeOrderId(), alloc.getAmountCents(),
                        "余额退款申请 " + req.getRequestNo(), alloc.getOutRefundNo());
                succeeded.add(alloc);
            } catch (RuntimeException e) {
                failure = e;
                log.error("balance refund channel failed request={} rechargeOrder={} outRefundNo={}",
                        req.getRequestNo(), alloc.getRechargeOrderId(), alloc.getOutRefundNo(), e);
                break;
            }
        }
        if (failure == null) {
            return self.markRefundSuccess(req.getRequestId());
        }
        List<BalanceRefundAllocation> allSucceeded = new ArrayList<>(doneBefore);
        allSucceeded.addAll(succeeded);
        self.markRefundFailedAndReverse(req.getRequestId(), allSucceeded, failure);
        // 失败已落库（PARTIAL/FAILED + 冲回），这里沿用既有契约向上抛错
        throw new IllegalStateException("balance refund approve failed request=" + req.getRequestNo(), failure);
    }

    /**
     * H63 续退事务①：重查 PARTIAL 行 → 仅规划未成功切片 → 补扣未成功部分 → 落切片并置 PROCESSING。
     */
    @Transactional
    public List<BalanceRefundAllocation> debitRemainingAndMarkProcessing(long requestId) {
        BalanceRefundRequest req = requestMapper.selectByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在"));
        if (!STATUS_PARTIAL.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可续退");
        }
        List<BalanceRefundAllocation> done = allocationMapper.findByRequestId(requestId);
        Set<String> skipOrderIds = done.stream()
                .map(BalanceRefundAllocation::getRechargeOrderId)
                .collect(Collectors.toSet());
        int succeededCents = done.stream().mapToInt(BalanceRefundAllocation::getAmountCents).sum();
        int remainCents = Math.max(0, req.getAmountCents() - succeededCents);
        List<BalanceRefundAllocation> allocations =
                planChannelRefunds(req, remainCents, skipOrderIds, done.size());
        if (allocations.isEmpty()) {
            return allocations;
        }
        debitRemainingBalance(req, remainCents);
        for (BalanceRefundAllocation alloc : allocations) {
            allocationMapper.insert(alloc);
        }
        req.setStatus(STATUS_PROCESSING);
        req.setUpdatedAt(Instant.now());
        requestMapper.updateById(req);
        log.info("balance refund resumed request={} remain={} slices={}",
                req.getRequestNo(), remainCents, allocations.size());
        return allocations;
    }

    /** H63 续退补扣：失败冲回时资金已回到余额（未回冻结），故这里直接扣余额。 */
    private void debitRemainingBalance(BalanceRefundRequest req, int cents) {
        if (cents <= 0) {
            return;
        }
        UserAccount account = accountMapper.findByIdForUpdate(req.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        if (account.getBalanceCents() < cents) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        int before = account.getBalanceCents();
        account.setBalanceCents(before - cents);
        accountMapper.save(account);
        balanceLedgerService.change(req.getUserId(), -cents, BIZ_BALANCE_REFUND,
                String.valueOf(req.getRequestId()),
                "BALANCE_REFUND_RESUME:" + req.getRequestNo(),
                "余额退款续退补扣 " + req.getRequestNo());
    }

    /** 事务①：扣减余额 + 落切片与 PROCESSING。 */
    @Transactional
    public List<BalanceRefundAllocation> debitAndMarkProcessing(long requestId) {
        BalanceRefundRequest req = requirePendingForUpdate(requestId);
        List<BalanceRefundAllocation> allocations = allocateChannelRefunds(req);
        if (allocations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可原路退回的充值单");
        }
        debitAccountForApprovedRefund(req);
        for (BalanceRefundAllocation alloc : allocations) {
            allocationMapper.insert(alloc);
        }
        req.setStatus(STATUS_PROCESSING);
        req.setUpdatedAt(Instant.now());
        requestMapper.updateById(req);
        return allocations;
    }

    /** 事务②成功路径：置 REFUNDED 并审计。 */
    @Transactional
    public BalanceRefundRequest markRefundSuccess(long requestId) {
        BalanceRefundRequest req = requestMapper.selectByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在"));
        req.setStatus(STATUS_REFUNDED);
        req.setRefundedAt(Instant.now());
        req.setFailReason(null);
        req.setUpdatedAt(Instant.now());
        requestMapper.updateById(req);
        auditService.appendLog(req.getReviewerId() == null ? 0L : req.getReviewerId(),
                "BALANCE_REFUND_APPROVE", BIZ_BALANCE_REFUND, String.valueOf(req.getRequestId()),
                "通过并原路退款 " + req.getRequestNo()
                        + " ¥" + String.format("%.2f", req.getAmountCents() / 100.0));
        log.info("balance refund succeeded request={} amount={}", req.getRequestNo(), req.getAmountCents());
        return req;
    }

    /**
     * 事务②失败路径：未成功切片的扣减用 {@link BalanceLedgerService#change} 冲回，置 FAILED。
     * 已成功切片的钱已原路退给用户，不冲回（避免双退）。
     */
    @Transactional
    public BalanceRefundRequest markRefundFailedAndReverse(long requestId,
                                                           List<BalanceRefundAllocation> succeeded,
                                                           RuntimeException cause) {
        BalanceRefundRequest req = requestMapper.selectByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在"));
        int succeededCents = succeeded.stream().mapToInt(BalanceRefundAllocation::getAmountCents).sum();
        int reverseCents = Math.max(0, req.getAmountCents() - succeededCents);
        if (reverseCents > 0) {
            balanceLedgerService.change(req.getUserId(), reverseCents, "BALANCE_REFUND_REVERSAL",
                    String.valueOf(req.getRequestId()),
                    "BALANCE_REFUND_REVERSAL:" + req.getRequestNo(),
                    "余额退款渠道失败冲回 " + req.getRequestNo());
        }
        // H63: 有已成功切片时置 PARTIAL 保留进度（复审可续退剩余部分，且不可驳回）；
        // 全部失败仍置 FAILED
        req.setStatus(succeededCents > 0 ? STATUS_PARTIAL : STATUS_FAILED);
        req.setFailReason(cause.getMessage() == null ? "渠道退款失败" : cause.getMessage());
        req.setUpdatedAt(Instant.now());
        requestMapper.updateById(req);
        log.error("balance refund failed request={} reversed={} succeeded={}",
                req.getRequestNo(), reverseCents, succeededCents, cause);
        return req;
    }

    /** 纯规划：不调渠道、不落库；outRefundNo 稳定（重试不换号，渠道幂等）。 */
    private List<BalanceRefundAllocation> allocateChannelRefunds(BalanceRefundRequest req) {
        return planChannelRefunds(req, req.getAmountCents(), Set.of(), 0);
    }

    /**
     * H63 续退切片规划：remainCents 只含未成功部分；skipOrderIds 为已完成切片的充值单
     * （切片序号接续 startIndex，outRefundNo 重试间稳定）。未完成的切片只可能出现在
     * 原计划末尾，故跳过已完成单不会遗漏可退额度。
     */
    private List<BalanceRefundAllocation> planChannelRefunds(BalanceRefundRequest req,
                                                             int remainCents,
                                                             Set<String> skipOrderIds,
                                                             int startIndex) {
        int remain = remainCents;
        List<RechargeOrder> orders = rechargeOrderMapper.findRefundablePaidByUser(req.getUserId());
        List<BalanceRefundAllocation> allocations = new ArrayList<>();
        int sliceIndex = startIndex;
        for (RechargeOrder order : orders) {
            if (remain <= 0) {
                break;
            }
            if (skipOrderIds != null && skipOrderIds.contains(order.getOrderId())) {
                continue;
            }
            int refundable = order.getAmountCents() - Math.max(0, order.getRefundedCents());
            if (refundable > 0) {
                String channel = normalizeRefundChannel(order.getChannel());
                if (channel != null) {
                    int slice = Math.min(remain, refundable);
                    sliceIndex++;
                    String outRefundNo = "BR" + req.getRequestId() + "-S" + sliceIndex;
                    BalanceRefundAllocation alloc = new BalanceRefundAllocation();
                    alloc.setRequestId(req.getRequestId());
                    alloc.setRechargeOrderId(order.getOrderId());
                    alloc.setAmountCents(slice);
                    alloc.setChannel(channel);
                    alloc.setOutRefundNo(outRefundNo);
                    alloc.setCreatedAt(Instant.now());
                    allocations.add(alloc);
                    remain -= slice;
                }
            }
        }
        if (remain > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "可原路退回的充值不足，还差 ¥" + String.format("%.2f", remain / 100.0));
        }
        return allocations;
    }

    private static String normalizeRefundChannel(String channel) {
        String normalized = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        if (PayChannels.WECHAT.equals(normalized) || PayChannels.ALIPAY.equals(normalized)
                || "MOCK".equals(normalized)) {
            return normalized.isBlank() ? PayChannels.WECHAT : normalized;
        }
        return null;
    }

    private void debitAccountForApprovedRefund(BalanceRefundRequest req) {
        UserAccount account = accountMapper.findByIdForUpdate(req.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        int frozen = Math.max(0, account.getFrozenCents());
        int capture = Math.min(frozen, req.getAmountCents());
        int before = account.getBalanceCents();
        account.setBalanceCents(before - req.getAmountCents());
        account.setFrozenCents(frozen - capture);
        if (account.getBalanceCents() < 0 || account.getFrozenCents() < 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        accountMapper.save(account);
        balanceLedgerService.recordFreezeOnly(req.getUserId(), new BalanceLedgerService.BalanceFreezeCommand(
                req.getAmountCents(), BIZ_BALANCE_REFUND, String.valueOf(req.getRequestId()),
                "BALANCE_REFUND:" + req.getRequestNo(),
                "余额退款原路退回", before, account.getBalanceCents()));
    }

    private void releaseFreeze(Long userId, int amountCents, String bizKey) {
        UserAccount account = accountMapper.findByIdForUpdate(userId).orElse(null);
        if (account == null || amountCents <= 0) return;
        int frozen = Math.max(0, account.getFrozenCents());
        int release = Math.min(frozen, amountCents);
        account.setFrozenCents(frozen - release);
        accountMapper.save(account);
        if (release > 0) {
            balanceLedgerService.recordFreezeOnly(userId, new BalanceLedgerService.BalanceFreezeCommand(
                    release, "BALANCE_REFUND_RELEASE", null, "BALANCE_REFUND_RELEASE:" + bizKey,
                    "余额退款申请释放冻结", account.getBalanceCents(), account.getBalanceCents()));
        }
    }

    private int sumRefundableChannelCents(Long userId) {
        int sum = 0;
        for (RechargeOrder order : rechargeOrderMapper.findRefundablePaidByUser(userId)) {
            String channel = order.getChannel() == null ? "" : order.getChannel().trim().toUpperCase(Locale.ROOT);
            if (!PayChannels.WECHAT.equals(channel) && !PayChannels.ALIPAY.equals(channel)
                    && !"MOCK".equals(channel)) {
                continue;
            }
            sum += Math.max(0, order.getAmountCents() - Math.max(0, order.getRefundedCents()));
        }
        return sum;
    }

    private BalanceRefundRequestDto toDto(BalanceRefundRequest req) {
        return new BalanceRefundRequestDto(
                req.getRequestId() == null ? 0L : req.getRequestId(),
                req.getRequestNo(),
                req.getUserId(),
                req.getAmountCents(),
                req.getStatus(),
                req.getReason(),
                req.getReviewRemark(),
                req.getReviewerId(),
                req.getReviewedAt(),
                req.getFailReason(),
                req.getCreatedAt(),
                req.getUpdatedAt(),
                req.getRefundedAt()
        );
    }

    private static String trim(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    static String balanceRefundLockKey(Long userId) {
        return "balance:refund:" + userId;
    }

    private <T> T runWithBalanceRefundLock(Long userId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(balanceRefundLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "余额退款处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(balanceRefundLockKey(userId));
        }
    }
}
