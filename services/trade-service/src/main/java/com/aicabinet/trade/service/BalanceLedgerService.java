package com.aicabinet.trade.service;

import com.aicabinet.common.constants.PayChannels;
import com.aicabinet.common.dto.BalanceTransactionDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.repository.PaymentOperationRepository;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class BalanceLedgerService {
    private final UserAccountRepository accountRepository;
    private final PaymentOperationRepository operationRepository;

    public BalanceLedgerService(UserAccountRepository accountRepository,
                                PaymentOperationRepository operationRepository) {
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
        operation.setOrderId(businessId);
        operation.setOperationType(businessType);
        operation.setAmountCents(Math.abs(deltaCents));
        operation.setChannel(PayChannels.BALANCE);
        operation.setStatus("COMPLETED");
        operation.setIdempotencyKey(idempotencyKey);
        operation.setReason(trim(reason));
        operation.setUserId(userId);
        operation.setBalanceBeforeCents(before);
        operation.setBalanceAfterCents((int) next);
        return operationRepository.saveAndFlush(operation);
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
