package com.aicabinet.trade.repository;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShoppingSessionRepository extends JpaRepository<ShoppingSession, String> {

    Optional<ShoppingSession> findByIdempotencyKey(String idempotencyKey);

    List<ShoppingSession> findByDeviceIdAndStateIn(String deviceId, List<SessionState> states);

    Page<ShoppingSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ShoppingSession> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

    Page<ShoppingSession> findByStateOrderByCreatedAtDesc(SessionState state, Pageable pageable);

    Page<ShoppingSession> findByDeviceIdAndStateOrderByCreatedAtDesc(String deviceId, SessionState state, Pageable pageable);

    long countByStateIn(List<SessionState> states);

    long countByCreatedAtAfter(java.time.Instant since);

    long countByDeviceId(String deviceId);

    long countByDeviceIdAndStateIn(String deviceId, List<SessionState> states);

    long countByUserIdAndCreatedAtAfter(Long userId, java.time.Instant since);

    long countByUserIdAndStateAndCreatedAtAfter(Long userId, SessionState state, java.time.Instant since);
}
