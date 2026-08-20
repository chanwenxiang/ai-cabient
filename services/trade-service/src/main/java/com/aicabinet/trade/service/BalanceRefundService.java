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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class BalanceRefundService {

    private static final Logger log = LoggerFactory.getLogger(BalanceRefundService.class);
    private static final String STATUS_PENDING = "PENDING_REVIEW";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    private static final String STATUS_FAILED = "FAILED";

    private final BalanceRefundRequestMapper requestMapper;
    private final BalanceRefundAllocationMapper allocationMapper;
    private final UserAccountMapper accountMapper;
    private final RechargeOrderMapper rechargeOrderMapper;
    private final PaymentService paymentService;
    private final BalanceLedgerService balanceLedgerService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public BalanceRefundService(BalanceRefundRequestMapper requestMapper,
                                BalanceRefundAllocationMapper allocationMapper,
                                UserAccountMapper accountMapper,
                                RechargeOrderMapper rechargeOrderMapper,
                                PaymentService paymentService,
                                BalanceLedgerService balanceLedgerService,
                                PermissionService permissionService,
                                AdminAuditService auditService) {
        this.requestMapper = requestMapper;
        this.allocationMapper = allocationMapper;
        this.accountMapper = accountMapper;
        this.rechargeOrderMapper = rechargeOrderMapper;
        this.paymentService = paymentService;
        this.balanceLedgerService = balanceLedgerService;
        this.permissionService = permissionService;
        this.auditService = auditService;
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
        balanceLedgerService.recordFreezeOnly(userId, amountCents, "BALANCE_REFUND_FREEZE",
                null, "BALANCE_REFUND_FREEZE:" + userId + ":" + Instant.now().toEpochMilli(),
                "余额退款申请冻结", account.getBalanceCents(), account.getBalanceCents());

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

    @Transactional
    public BalanceRefundRequestDto review(Long operatorId, long requestId, boolean approve, String remark) {
        permissionService.requirePermission(operatorId, "ops:balance-refund:review");
        BalanceRefundRequest req = requestMapper.selectById(requestId);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "退款申请不存在");
        }
        if (!STATUS_PENDING.equals(req.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可审核");
        }
        Instant now = Instant.now();
        req.setReviewerId(operatorId);
        req.setReviewRemark(trim(remark));
        req.setReviewedAt(now);
        req.setUpdatedAt(now);

        if (!approve) {
            releaseFreeze(req.getUserId(), req.getAmountCents(), req.getRequestNo());
            req.setStatus(STATUS_REJECTED);
            requestMapper.updateById(req);
            auditService.record(operatorId, "BALANCE_REFUND_REJECT", "BALANCE_REFUND",
                    String.valueOf(requestId), "驳回 " + req.getRequestNo());
            return toDto(req);
        }

        try {
            executeApprovedRefund(req);
            req.setStatus(STATUS_REFUNDED);
            req.setRefundedAt(Instant.now());
            req.setFailReason(null);
            requestMapper.updateById(req);
            auditService.record(operatorId, "BALANCE_REFUND_APPROVE", "BALANCE_REFUND",
                    String.valueOf(requestId), "通过并原路退款 " + req.getRequestNo()
                            + " ¥" + String.format("%.2f", req.getAmountCents() / 100.0));
        } catch (RuntimeException e) {
            log.warn("balance refund approve failed request={}: {}", req.getRequestNo(), e.getMessage());
            req.setStatus(STATUS_FAILED);
            req.setFailReason(e.getMessage() == null ? "退款失败" : e.getMessage());
            requestMapper.updateById(req);
            // 失败时保持冻结，避免用户继续花掉；运营可驳回释放或再次审核（需先改状态）
            // 这里自动释放冻结，避免卡死；运营可让用户重新申请
            releaseFreeze(req.getUserId(), req.getAmountCents(), req.getRequestNo() + ":fail");
            throw e;
        }
        return toDto(req);
    }

    private void executeApprovedRefund(BalanceRefundRequest req) {
        int remain = req.getAmountCents();
        List<RechargeOrder> orders = rechargeOrderMapper.findRefundablePaidByUser(req.getUserId());
        List<BalanceRefundAllocation> allocations = new ArrayList<>();
        for (RechargeOrder order : orders) {
            if (remain <= 0) break;
            int refundable = order.getAmountCents() - Math.max(0, order.getRefundedCents());
            if (refundable <= 0) continue;
            String channel = order.getChannel() == null ? "" : order.getChannel().trim().toUpperCase(Locale.ROOT);
            if (!PayChannels.WECHAT.equals(channel) && !PayChannels.ALIPAY.equals(channel)
                    && !"MOCK".equals(channel)) {
                continue;
            }
            int slice = Math.min(remain, refundable);
            String outRefundNo = "BR" + req.getRequestId() + "R"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            paymentService.refundRechargeChannelPartial(
                    order.getOrderId(), slice,
                    "余额退款申请 " + req.getRequestNo(),
                    outRefundNo);
            BalanceRefundAllocation alloc = new BalanceRefundAllocation();
            alloc.setRequestId(req.getRequestId());
            alloc.setRechargeOrderId(order.getOrderId());
            alloc.setAmountCents(slice);
            alloc.setChannel(channel.isBlank() ? PayChannels.WECHAT : channel);
            alloc.setOutRefundNo(outRefundNo);
            alloc.setCreatedAt(Instant.now());
            allocationMapper.insert(alloc);
            allocations.add(alloc);
            remain -= slice;
        }
        if (remain > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "可原路退回的充值不足，还差 ¥" + String.format("%.2f", remain / 100.0));
        }
        if (allocations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可原路退回的充值单");
        }

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
        balanceLedgerService.recordFreezeOnly(req.getUserId(), req.getAmountCents(), "BALANCE_REFUND",
                String.valueOf(req.getRequestId()),
                "BALANCE_REFUND:" + req.getRequestNo(),
                "余额退款原路退回", before, account.getBalanceCents());
    }

    private void releaseFreeze(Long userId, int amountCents, String bizKey) {
        UserAccount account = accountMapper.findByIdForUpdate(userId).orElse(null);
        if (account == null || amountCents <= 0) return;
        int frozen = Math.max(0, account.getFrozenCents());
        int release = Math.min(frozen, amountCents);
        account.setFrozenCents(frozen - release);
        accountMapper.save(account);
        if (release > 0) {
            balanceLedgerService.recordFreezeOnly(userId, release, "BALANCE_REFUND_RELEASE",
                    null, "BALANCE_REFUND_RELEASE:" + bizKey,
                    "余额退款申请释放冻结", account.getBalanceCents(), account.getBalanceCents());
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
}
