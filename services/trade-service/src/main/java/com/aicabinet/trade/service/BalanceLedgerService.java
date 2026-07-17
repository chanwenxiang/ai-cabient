package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.BalanceTransactionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class BalanceLedgerService {
    private final UserAccountMapper accountRepository;
    private final PaymentOperationMapper operationRepository;

    public BalanceLedgerService(UserAccountMapper accountRepository,
                                PaymentOperationMapper operationRepository) {
        this.accountRepository = accountRepository;
        this.operationRepository = operationRepository;
    }

    @Transactional
    public PaymentOperation change(Long userId, int deltaCents, String businessType,
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
        if (next > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        }
        account.setBalanceCents((int) next);
        accountRepository.save(account);

        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId("BL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
        // order_id FK → cabinet_order；充值/运营调账等业务单号不能写入该列
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

    /** 仅购物扣款/退款类流水挂接 cabinet_order，避免充值单号触发 FK 失败。 */
    private static String resolveCabinetOrderId(String businessType, String businessId) {
        if (businessId == null || businessId.isBlank()) return null;
        return switch (businessType) {
            case "CHARGE", "REFUND", "ADJUST_CHARGE" -> businessId;
            default -> null;
        };
    }

    private static String buildReason(String businessType, String businessId, String reason) {
        String base = trim(reason);
        if (businessId == null || businessId.isBlank()) return base;
        if ("CHARGE".equals(businessType) || "REFUND".equals(businessType) || "ADJUST_CHARGE".equals(businessType)) {
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
                    case "CHARGE", "ADJUST_CHARGE" -> -operation.getAmountCents();
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
