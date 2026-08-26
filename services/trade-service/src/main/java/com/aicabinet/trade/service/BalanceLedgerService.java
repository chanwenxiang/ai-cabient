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
    public PaymentOperation recordFreezeOnly(Long userId, int amountCents, String businessType,
                                             String businessId, String idempotencyKey,
                                             String reason, int balanceBefore, int balanceAfter) {
        return runWithBalanceLock(userId, () -> doRecordFreezeOnly(userId, amountCents, businessType,
                businessId, idempotencyKey, reason, balanceBefore, balanceAfter));
    }

    private PaymentOperation doRecordFreezeOnly(Long userId, int amountCents, String businessType,
                                                String businessId, String idempotencyKey,
                                                String reason, int balanceBefore, int balanceAfter) {
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        var existing = operationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId(BizIds.nextNumeric());
        operation.setOrderId(null);
        operation.setOperationType(businessType);
        operation.setAmountCents(amountCents);
        operation.setChannel(PayChannels.BALANCE);
        operation.setStatus("COMPLETED");
        operation.setIdempotencyKey(idempotencyKey);
        operation.setReason(buildReason(businessType, businessId, reason));
        operation.setUserId(userId);
        operation.setBalanceBeforeCents(balanceBefore);
        operation.setBalanceAfterCents(balanceAfter);
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

    private BalanceTransactionDto toDto(PaymentOperation operation) {
        int signedAmount = operation.getBalanceBeforeCents() != null && operation.getBalanceAfterCents() != null
                ? operation.getBalanceAfterCents() - operation.getBalanceBeforeCents()
                : switch (operation.getOperationType()) {
                    case CHARGE, ADJUST_CHARGE -> -operation.getAmountCents();
                    default -> operation.getAmountCents();
                };
        return new BalanceTransactionDto(operation.getOperationId(), operation.getUserId(),
                operation.getOperationType(), operation.getOrderId(), signedAmount,
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
