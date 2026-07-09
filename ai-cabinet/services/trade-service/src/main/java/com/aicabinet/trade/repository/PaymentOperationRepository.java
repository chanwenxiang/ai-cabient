package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.PaymentOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, String> {
    Optional<PaymentOperation> findByIdempotencyKey(String idempotencyKey);
}
