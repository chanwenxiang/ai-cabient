package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.CabinetOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface CabinetOrderRepository extends JpaRepository<CabinetOrder, String> {

    Optional<CabinetOrder> findBySessionId(String sessionId);

    Page<CabinetOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<CabinetOrder> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

    Page<CabinetOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    java.util.List<CabinetOrder> findByCreatedAtAfter(Instant since);

    long countByCreatedAtAfter(Instant since);

    @Query("SELECT COALESCE(SUM(o.totalAmountCents), 0) FROM CabinetOrder o WHERE o.createdAt >= :since")
    long sumTotalAmountSince(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(o.totalAmountCents), 0) FROM CabinetOrder o")
    long sumTotalAmount();

    long countByDeviceId(String deviceId);

    long countByDeviceIdAndCreatedAtAfter(String deviceId, Instant since);

    @Query("SELECT COALESCE(SUM(o.totalAmountCents), 0) FROM CabinetOrder o WHERE o.deviceId = :deviceId")
    long sumAmountByDeviceId(@Param("deviceId") String deviceId);

    @Query("SELECT COALESCE(SUM(o.totalAmountCents), 0) FROM CabinetOrder o WHERE o.deviceId = :deviceId AND o.createdAt >= :since")
    long sumAmountByDeviceIdSince(@Param("deviceId") String deviceId, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(o.totalAmountCents), 0) FROM CabinetOrder o WHERE o.createdAt >= :start AND o.createdAt < :end")
    long sumTotalAmountBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT o.orderId FROM CabinetOrder o WHERE o.createdAt >= :start AND o.createdAt < :end")
    java.util.List<String> findOrderIdsBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT o FROM CabinetOrder o WHERE o.createdAt >= :start AND o.createdAt < :end")
    java.util.List<CabinetOrder> findByCreatedAtBetween(@Param("start") Instant start, @Param("end") Instant end);
}
