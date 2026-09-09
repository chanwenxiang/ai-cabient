package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineWalletOverviewDto;
import com.aicabinet.common.dto.LineWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Set;
import java.util.UUID;

@Service
public class LineWithdrawService {
    private static final String PERM_OPS_LINE_WITHDRAW_REVIEW = "ops:line-withdraw:review";
    private static final String LINE_WITHDRAW_REVIEW = "LINE_WITHDRAW_REVIEW";
    private static final String WITHDRAW = "WITHDRAW";
    private static final String STATUS_APPROVED = "APPROVED";


    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LineWithdrawRequestMapper withdrawMapper;
    private final LineManagerMapper managerMapper;
    private final LineDeviceMapper deviceMapper;
    private final LineManagerService lineManagerService;
    private final LineWalletService lineWalletService;
    private final LineWithdrawPayoutService payoutService;
    private final LineWithdrawProperties properties;
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
                               @Lazy LineWithdrawService self) {
        this.withdrawMapper = withdrawMapper;
        this.managerMapper = managerMapper;
        this.deviceMapper = deviceMapper;
        this.lineManagerService = lineManagerService;
        this.lineWalletService = lineWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
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
        return createWithdraw(manager, amountCents, requestNo, null);
    }

    public LineWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        LineManager manager = lineManagerService.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未绑定线长身份"));
        if (!LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "线长账号不可用");
        }
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
        validateAmount(manager.getManagerId(), amountCents);
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
                amountCents, properties.feeCents(), properties.feeBps()));
        request.setPayChannel(properties.mockEnabled() ? "MOCK" : "WECHAT");
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= properties.reviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
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
        lineWalletService.freezeForWithdraw(manager.getManagerId(), amountCents,
                WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
        return PayoutGate.needPayout(toDto(request));
    }

    public LineWithdrawRequestDto executePayout(long requestId) {
        LineWithdrawRequest paying = self.markPaying(requestId);
        LineManager manager = lineManagerService.requireManager(paying.getManagerId());
        LineWithdrawPayoutService.PayoutResult result = payoutService.payout(paying, manager);
        return self.finalizePayout(requestId, result);
    }

    @Transactional
    public LineWithdrawRequest markPaying(long requestId) {
        LineWithdrawRequest request = requireRequest(requestId);
        if (!Set.of(STATUS_APPROVED, "FAILED", "PAYING").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        request.setStatus("PAYING");
        request.setUpdatedAt(Instant.now());
        withdrawMapper.updateById(request);
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
            lineWalletService.consumeFrozen(request.getManagerId(), request.getAmountCents(),
                    WITHDRAW, String.valueOf(request.getRequestId()), "提现打款成功");
            return toDto(request);
        }
        request.setStatus("FAILED");
        withdrawMapper.updateById(request);
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

    private void validateAmount(long managerId, long amountCents) {
        if (amountCents < properties.minAmountCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "最低提现 " + (properties.minAmountCents() / 100.0) + " 元");
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
        if (used + amountCents > properties.dailyLimitCents()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "超过单日提现限额");
        }
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
                request.getFeeCents() == null ? 0L : request.getFeeCents()
        );
    }

    private static String normalizeRequestNo(String requestNo) {
        if (requestNo != null && !requestNo.isBlank()) {
            return requestNo.trim();
        }
        return "LW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
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
