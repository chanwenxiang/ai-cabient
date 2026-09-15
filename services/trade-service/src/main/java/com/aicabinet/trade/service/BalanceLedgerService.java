package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.BalanceTransactionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.util.BizIds;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BalanceLedgerService {
    private static final String ADJUST_CHARGE = "ADJUST_CHARGE";
    private static final String CHARGE = "CHARGE";

    private final UserAccountMapper accountRepository;
    private final PaymentOperationMapper operationRepository;
    private final DistributedLockService distributedLockService;

    public BalanceLedgerService(UserAccountMapper accountRepository,
                                PaymentOperationMapper operationRepository,
                                DistributedLockService distributedLockService) {
        this.accountRepository = accountRepository;
        this.operationRepository = operationRepository;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public PaymentOperation change(Long userId, int deltaCents, String businessType,
                                   String businessId, String idempotencyKey, String reason) {
        return runWithBalanceLock(userId, () -> doChange(userId, deltaCents, businessType,
                businessId, idempotencyKey, reason));
    }

    private PaymentOperation doChange(Long userId, int deltaCents, String businessType,
                                      String businessId, String idempotencyKey, String reason) {
        if (deltaCents == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        var existing = operationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();

        UserAccount account = accountRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.ACCOUNT_NOT_FOUND));
        existing = operationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();
        int before = account.getBalanceCents();
        long next = (long) before + deltaCents;
        if (next < 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
        }
        if (deltaCents < 0) {
            int frozen = Math.max(0, account.getFrozenCents());
            if ((long) before - frozen + deltaCents < 0) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE);
            }
        }
        if (next > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        account.setBalanceCents((int) next);
        accountRepository.save(account);

        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId(BizIds.nextNumeric());
        operation.setOrderId(resolveCabinetOrderId(businessType, businessId));
        operation.setOperationType(businessType);
        operation.setAmountCents(Math.abs(deltaCents));
        operation.setChannel(PayChannels.BALANCE);
        operation.setStatus("COMPLETED");
        operation.setIdempotencyKey(idempotencyKey);
        operation.setReason(buildReason(businessType, businessId, reason));
        operation.setUserId(userId);
        operation.setBalanceBeforeCents(before);
        operation.setBalanceAfterCents((int) next);
        return operationRepository.saveAndFlush(operation);
    }

    /**
     * 记录预授权冻结/释放/冲抵流水（余额字段仅作审计快照；冻结额变更由调用方完成）。
     */
    @Transactional
    public PaymentOperation recordFreezeOnly(Long userId, BalanceFreezeCommand command) {
        return runWithBalanceLock(userId, () -> doRecordFreezeOnly(userId, command));
    }

    public record BalanceFreezeCommand(
            int amountCents, String businessType, String businessId, String idempotencyKey,
            String reason, int balanceBefore, int balanceAfter) {}

    private PaymentOperation doRecordFreezeOnly(Long userId, BalanceFreezeCommand command) {
        if (command.amountCents() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        var existing = operationRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId(BizIds.nextNumeric());
        operation.setOrderId(null);
        operation.setOperationType(command.businessType());
        operation.setAmountCents(command.amountCents());
        operation.setChannel(PayChannels.BALANCE);
        operation.setStatus("COMPLETED");
        operation.setIdempotencyKey(command.idempotencyKey());
        operation.setReason(buildReason(command.businessType(), command.businessId(), command.reason()));
        operation.setUserId(userId);
        operation.setBalanceBeforeCents(command.balanceBefore());
        operation.setBalanceAfterCents(command.balanceAfter());
        return operationRepository.saveAndFlush(operation);
    }

    static String balanceLockKey(long userId) {
        return AdminDashboardService.userBalanceLockKey(userId);
    }

    private <T> T runWithBalanceLock(long userId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(balanceLockKey(userId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "余额处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(balanceLockKey(userId));
        }
    }

    /** 仅购物扣款/退款类流水挂接 cabinet_order，避免充值单号触发 FK 失败。 */
    private static String resolveCabinetOrderId(String businessType, String businessId) {
        if (businessId == null || businessId.isBlank()) return null;
        return switch (businessType) {
            case CHARGE, "REFUND", ADJUST_CHARGE -> businessId;
            default -> null;
        };
    }

    private static String buildReason(String businessType, String businessId, String reason) {
        String base = trim(reason);
        if (businessId == null || businessId.isBlank()) return base;
        if (CHARGE.equals(businessType) || "REFUND".equals(businessType) || ADJUST_CHARGE.equals(businessType)) {
            return base;
        }
        String suffix = "#" + businessId;
        if (base == null || base.isBlank()) return trim(suffix);
        if (base.contains(businessId)) return base;
        return trim(base + " " + suffix);
    }

    @Transactional(readOnly = true)
    public PageResult<BalanceTransactionDto> list(Long userId, int page, int size) {
        var result = operationRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return new PageResult<>(result.getContent().stream().map(this::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /**
     * 计算流水的「带符号金额」。
     *
     * <p>优先以可用余额差值（after - before）为准，它天然覆盖购物扣款、退款、预授权冲抵、
     * 充值等所有真正改动用例余额的操作。
     *
     * <p>但 {@code recordFreezeOnly} 写入的**纯冻结 / 纯释放**流水（{@code PREAUTH_FREEZE}、
     * {@code PREAUTH_RELEASE}、{@code BALANCE_REFUND_FREEZE}、{@code BALANCE_REFUND_RELEASE}）
     * 只调整账户冻结额，可用余额前后一致，余额差恒为 0 —— 若沿用上面的算法会让这些流水
     * 全部显示成「¥0.00」，这正是 W-4。此类流水改为取操作金额并按业务方向取符号：
     * 冻结/申请冻结为流出（负），释放/解冻为流入（正）。
     *
     * <p>一致性依据：{@code doRecordFreezeOnly} 的调用方在 {@code capture > 0} / {@code release > 0}
     * 时才落库，故「余额差为 0」与「纯冻结/释放」严格等价，不会误判其它类型。
     */
    private static int resolveSignedAmount(PaymentOperation operation) {
        Integer before = operation.getBalanceBeforeCents();
        Integer after = operation.getBalanceAfterCents();
        if (before != null && after != null) {
            if (!before.equals(after)) {
                return after - before;
            }
            Integer holdAmount = holdSignedAmount(operation.getOperationType(), operation.getAmountCents());
            // 余额未变的已知冻结/释放类型 → 用操作金额表达；未知类型维持原有 0 语义
            return holdAmount != null ? holdAmount : 0;
        }
        // 历史数据缺失余额快照：沿用按操作类型的兜底
        return switch (operation.getOperationType() == null ? "" : operation.getOperationType()) {
            case CHARGE, ADJUST_CHARGE -> -operation.getAmountCents();
            default -> operation.getAmountCents();
        };
    }

    /** 纯冻结/释放类型的带符号金额；非该类返回 {@code null}。 */
    private static Integer holdSignedAmount(String operationType, int amountCents) {
        if (operationType == null) {
            return null;
        }
        return switch (operationType) {
            case "PREAUTH_FREEZE", "BALANCE_REFUND_FREEZE" -> -amountCents;
            case "PREAUTH_RELEASE", "BALANCE_REFUND_RELEASE" -> amountCents;
            default -> null;
        };
    }

    private BalanceTransactionDto toDto(PaymentOperation operation) {
        return new BalanceTransactionDto(operation.getOperationId(), operation.getUserId(),
                operation.getOperationType(), operation.getOrderId(), resolveSignedAmount(operation),
                value(operation.getBalanceBeforeCents()), value(operation.getBalanceAfterCents()),
                operation.getReason(), operation.getCreatedAt());
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
    }
}
