package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineWalletOverviewDto;
import com.aicabinet.common.dto.LineWithdrawRequestDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LineWithdrawRequestMapper withdrawMapper;
    private final LineManagerMapper managerMapper;
    private final LineManagerService lineManagerService;
    private final LineWalletService lineWalletService;
    private final LineWithdrawPayoutService payoutService;
    private final LineWithdrawProperties properties;
    private final PermissionService permissionService;

    public LineWithdrawService(LineWithdrawRequestMapper withdrawMapper,
                               LineManagerMapper managerMapper,
                               LineManagerService lineManagerService,
                               LineWalletService lineWalletService,
                               LineWithdrawPayoutService payoutService,
                               LineWithdrawProperties properties,
                               PermissionService permissionService) {
        this.withdrawMapper = withdrawMapper;
        this.managerMapper = managerMapper;
        this.lineManagerService = lineManagerService;
        this.lineWalletService = lineWalletService;
        this.payoutService = payoutService;
        this.properties = properties;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public PageResult<LineWithdrawRequestDto> list(Long operatorId, String status, Long managerId, int page, int size) {
        permissionService.requireAnyPermission(operatorId,
                "ops:line-manager:list", "ops:line-withdraw:review", "ops:finance:view");
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
        return createWithdraw(manager, amountCents, requestNo);
    }

    @Transactional
    public LineWithdrawRequestDto merchantApply(Long userId, long amountCents, String requestNo) {
        LineManager manager = lineManagerService.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未绑定线长身份"));
        if (!LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "线长账号不可用");
        }
        return createWithdraw(manager, amountCents, requestNo);
    }

    @Transactional
    public LineWithdrawRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, "ops:line-withdraw:review");
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
            request.setStatus("REJECTED");
            withdrawMapper.updateById(request);
            lineWalletService.releaseFrozen(request.getManagerId(), request.getAmountCents(),
                    "WITHDRAW", String.valueOf(request.getRequestId()), "提现驳回释放");
            return toDto(request);
        }
        request.setStatus("APPROVED");
        withdrawMapper.updateById(request);
        return attemptPayout(request, operatorId);
    }

    @Transactional
    public LineWithdrawRequestDto payout(Long operatorId, long requestId) {
        permissionService.requirePermission(operatorId, "ops:line-withdraw:review");
        LineWithdrawRequest request = requireRequest(requestId);
        if (!Set.of("APPROVED", "FAILED").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可打款");
        }
        return attemptPayout(request, operatorId);
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

    private LineWithdrawRequestDto createWithdraw(LineManager manager, long amountCents, String requestNo) {
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
        request.setPayChannel(properties.mockEnabled() ? "MOCK" : "WECHAT");
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        if (amountCents >= properties.reviewThresholdCents()) {
            request.setStatus("PENDING_REVIEW");
            withdrawMapper.insert(request);
            lineWalletService.freezeForWithdraw(manager.getManagerId(), amountCents,
                    "WITHDRAW", String.valueOf(request.getRequestId()), "提现申请冻结");
            return toDto(request);
        }
        request.setStatus("APPROVED");
        request.setReviewRemark("低于审核阈值自动通过");
        request.setReviewedAt(now);
        withdrawMapper.insert(request);
        lineWalletService.freezeForWithdraw(manager.getManagerId(), amountCents,
                "WITHDRAW", String.valueOf(request.getRequestId()), "提现申请冻结");
        return attemptPayout(request, null);
    }

    private LineWithdrawRequestDto attemptPayout(LineWithdrawRequest request, Long operatorId) {
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
                    "WITHDRAW", String.valueOf(request.getRequestId()), "提现打款成功");
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
                request.getUpdatedAt()
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

}
