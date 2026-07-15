package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.RiskEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {
    Page<RiskEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByUserIdAndEventTypeAndCreatedAtAfter(Long userId, String eventType, Instant since);
    long countByUserIdAndCreatedAtAfter(Long userId, Instant since);
}
