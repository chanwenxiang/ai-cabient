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
                               ApprovalWorkflowService approvalWorkflowService) {
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

    @Transactional
    public LineWithdrawRequestDto apply(long managerId, long amountCents, String requestNo) {
        LineManager manager = lineManagerService.requireManager(managerId);
        return createWithdraw(manager, amountCents, requestNo, null);
    }

    @Transactional
    public LineWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        LineManager manager = lineManagerService.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未绑定线长身份"));
        if (!LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "线长账号不可用");
        }
        return createWithdraw(manager, amountCents, requestNo, userId);
    }

    @Transactional
    public LineWithdrawRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_WITHDRAW_REVIEW);
        LineWithdrawRequest request = requireRequest(requestId);
        return runWithLineWalletLock(request.getManagerId(),
                () -> doReview(operatorId, request, approve, remark));
    }

    private LineWithdrawRequestDto doReview(Long operatorId, LineWithdrawRequest request,
                                            boolean approve, String remark) {
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
            return toDto(request);
        }
        approvalWorkflowService.completeApproved(
                operatorId, BIZ_LINE_WITHDRAW, String.valueOf(request.getRequestId()), trim(remark));
        if (!approvalWorkflowService.isInstanceApproved(
                BIZ_LINE_WITHDRAW, String.valueOf(request.getRequestId()))) {
            auditService.appendLog(operatorId, LINE_WITHDRAW_REVIEW, BIZ_LINE_WITHDRAW,
                    String.valueOf(request.getRequestId()), "初审通过；金额(分)=" + request.getAmountCents());
            return toDto(request);
        }
        request.setStatus(STATUS_APPROVED);
        withdrawMapper.updateById(request);
        auditService.appendLog(operatorId, LINE_WITHDRAW_REVIEW, BIZ_LINE_WITHDRAW,
                String.valueOf(request.getRequestId()), "通过；金额(分)=" + request.getAmountCents()
                        + "；备注=" + trim(remark));
        return attemptPayout(request);
    }

    @Transactional
    public LineWithdrawRequestDto payout(Long operatorId, long requestId) {
        permissionService.requirePermission(operatorId, PERM_OPS_LINE_WITHDRAW_REVIEW);
        LineWithdrawRequest request = requireRequest(requestId);
        return runWithLineWalletLock(request.getManagerId(), () -> {
            if (!Set.of(STATUS_APPROVED, "FAILED").contains(request.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
            }
            auditService.appendLog(operatorId, "LINE_WITHDRAW_PAYOUT", BIZ_LINE_WITHDRAW,
                    String.valueOf(requestId), "打款金额(分)=" + request.getAmountCents());
            return attemptPayout(request);
        });
    }

    @Transactional(readOnly = true)
    public LineWalletOverviewDto merchantOverview(Long userId) {
        return lineManagerService.findByUserId(userId)
                .map(manager -> {
                    LineWalletAccount account = lineWalletService.ensureAccount(manager.getManagerId());
                    long balance = value(account.getBalanceCents());
                    long frozen = value(account.getFrozenCents());
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
        return runWithLineWalletLock(manager.getManagerId(),
                () -> doCreateWithdraw(manager, amountCents, requestNo, submitterUserId));
    }

    private LineWithdrawRequestDto doCreateWithdraw(LineManager manager, long amountCents, String requestNo,
                                                    Long submitterUserId) {
        validateAmount(manager.getManagerId(), amountCents);
        String no = normalizeRequestNo(requestNo);
        var existing = withdrawMapper.findByRequestNo(no);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Instant now = Instant.now();
        LineWithdrawRequest request = new LineWithdrawRequest();
        request.setRequestNo(no);
        request.setManagerId(manager.getManagerId());
        request.setAmountCents(amountCents);
        request.setFeeCents(0L);
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
            return toDto(request);
        }
        request.setStatus(STATUS_APPROVED);
        request.setReviewRemark("低于审核阈值自动通过");
        request.setReviewedAt(now);
        withdrawMapper.insert(request);
        lineWalletService.freezeForWithdraw(manager.getManagerId(), amountCents,
                WITHDRAW, String.valueOf(request.getRequestId()), "提现申请冻结");
        return attemptPayout(request);
    }

    private LineWithdrawRequestDto attemptPayout(LineWithdrawRequest request) {
        LineManager manager = lineManagerService.requireManager(request.getManagerId());
        request.setStatus("PAYING");
        request.setUpdatedAt(Instant.now());
        withdrawMapper.updateById(request);

        LineWithdrawPayoutService.PayoutResult result = payoutService.payout(request, manager);
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
