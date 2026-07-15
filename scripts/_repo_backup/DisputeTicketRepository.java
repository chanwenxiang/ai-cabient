package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DisputeTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DisputeTicketRepository extends JpaRepository<DisputeTicket, String> {

    Optional<DisputeTicket> findBySessionId(String sessionId);

    List<DisputeTicket> findByStatusOrderByCreatedAtDesc(String status);

    List<DisputeTicket> findTop10ByStatusOrderBySlaDueAtAscCreatedAtAsc(String status);

    long countByStatus(String status);

    long countByCreatedAtAfter(Instant since);

    @Query("""
            SELECT d FROM DisputeTicket d
            JOIN ShoppingSession s ON d.sessionId = s.sessionId
            WHERE (:status IS NULL OR :status = '' OR d.status = :status)
              AND (:sessionId IS NULL OR :sessionId = '' OR d.sessionId = :sessionId)
              AND (:deviceId IS NULL OR :deviceId = '' OR s.deviceId = :deviceId)
            ORDER BY d.createdAt DESC
            """)
    Page<DisputeTicket> search(
            @Param("status") String status,
            @Param("sessionId") String sessionId,
            @Param("deviceId") String deviceId,
            Pageable pageable);

    @Query("""
            SELECT d FROM DisputeTicket d
            JOIN ShoppingSession s ON d.sessionId = s.sessionId
            WHERE s.userId = :userId
            ORDER BY d.createdAt DESC
            """)
    List<DisputeTicket> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("""
            SELECT d FROM DisputeTicket d
            JOIN ShoppingSession s ON d.sessionId = s.sessionId
            WHERE (:status IS NULL OR :status = '' OR d.status = :status)
              AND (:sessionId IS NULL OR :sessionId = '' OR d.sessionId = :sessionId)
              AND s.deviceId IN :deviceIds
            ORDER BY d.createdAt DESC
            """)
    Page<DisputeTicket> searchByDeviceIds(
            @Param("status") String status,
            @Param("sessionId") String sessionId,
            @Param("deviceIds") Collection<String> deviceIds,
            Pageable pageable);

    @Query("""
            SELECT COUNT(d) FROM DisputeTicket d
            JOIN ShoppingSession s ON d.sessionId = s.sessionId
            WHERE d.status = 'OPEN' AND s.deviceId IN :deviceIds
            """)
    long countOpenByDeviceIds(@Param("deviceIds") Collection<String> deviceIds);

    @Query("""
            SELECT COUNT(d) FROM DisputeTicket d
            WHERE d.status = 'OPEN' AND d.slaDueAt IS NOT NULL AND d.slaDueAt < :now
            """)
    long countOverdue(@Param("now") Instant now);

    @Query("""
            SELECT COUNT(d) FROM DisputeTicket d
            WHERE d.status = 'OPEN' AND d.slaDueAt IS NOT NULL
              AND d.slaDueAt >= :now AND d.slaDueAt <= :threshold
            """)
    long countNearSla(@Param("now") Instant now, @Param("threshold") Instant threshold);

    @Query("""
            SELECT COUNT(d) FROM DisputeTicket d
            WHERE d.status = 'RESOLVED' AND d.resolvedAt >= :since
            """)
    long countResolvedSince(@Param("since") Instant since);

    @Query("""
            SELECT COUNT(d) FROM DisputeTicket d
            WHERE d.status = 'RESOLVED' AND d.resolvedAt >= :since
              AND (d.slaDueAt IS NULL OR d.resolvedAt <= d.slaDueAt)
            """)
    long countResolvedWithinSlaSince(@Param("since") Instant since);
}
