package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DisputeTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DisputeTicketRepository extends JpaRepository<DisputeTicket, String> {

    Optional<DisputeTicket> findBySessionId(String sessionId);

    List<DisputeTicket> findByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);

    @Query("""
            SELECT d FROM DisputeTicket d
            JOIN ShoppingSession s ON d.sessionId = s.sessionId
            WHERE s.userId = :userId
            ORDER BY d.createdAt DESC
            """)
    List<DisputeTicket> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
}
