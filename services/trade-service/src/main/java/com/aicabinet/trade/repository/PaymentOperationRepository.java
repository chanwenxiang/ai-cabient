package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.PaymentOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, String> {
    Optional<PaymentOperation> findByIdempotencyKey(String idempotencyKey);
    Page<PaymentOperation> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
