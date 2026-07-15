package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.RechargeOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, String> {

    Optional<RechargeOrder> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT COALESCE(SUM(r.amountCents), 0) FROM RechargeOrder r WHERE r.status = 'PAID' AND r.paidAt >= :start AND r.paidAt < :end")
    long sumPaidAmountBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT r.orderId FROM RechargeOrder r WHERE r.status = 'PAID' AND r.paidAt >= :start AND r.paidAt < :end")
    List<String> findPaidOrderIdsBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT r FROM RechargeOrder r WHERE r.status = 'PAID' AND r.paidAt >= :start AND r.paidAt < :end")
    List<RechargeOrder> findPaidBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            SELECT r FROM RechargeOrder r
            WHERE (:status IS NULL OR :status = '' OR r.status = :status)
              AND (:userId IS NULL OR r.userId = :userId)
            ORDER BY r.createdAt DESC
            """)
    Page<RechargeOrder> search(
            @Param("status") String status,
            @Param("userId") Long userId,
            Pageable pageable);
}
