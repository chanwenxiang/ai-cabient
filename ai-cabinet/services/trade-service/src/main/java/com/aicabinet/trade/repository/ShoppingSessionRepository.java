package com.aicabinet.trade.repository;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ShoppingSessionRepository extends JpaRepository<ShoppingSession, String> {

    Optional<ShoppingSession> findByIdempotencyKey(String idempotencyKey);

    List<ShoppingSession> findByDeviceIdAndStateIn(String deviceId, List<SessionState> states);

    Page<ShoppingSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ShoppingSession> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

    Page<ShoppingSession> findByStateOrderByCreatedAtDesc(SessionState state, Pageable pageable);

    List<ShoppingSession> findTop10ByStateOrderByUpdatedAtAsc(SessionState state);

    Page<ShoppingSession> findByDeviceIdAndStateOrderByCreatedAtDesc(String deviceId, SessionState state, Pageable pageable);

    long countByStateIn(List<SessionState> states);

    long countByCreatedAtAfter(java.time.Instant since);

    long countByDeviceId(String deviceId);

    long countByDeviceIdAndStateIn(String deviceId, List<SessionState> states);

    long countByUserIdAndCreatedAtAfter(Long userId, java.time.Instant since);

    long countByUserIdAndStateAndCreatedAtAfter(Long userId, SessionState state, java.time.Instant since);

    long countByState(SessionState state);

    long countByStateAndUpdatedAtAfter(SessionState state, java.time.Instant since);

    Page<ShoppingSession> findByDeviceIdInOrderByCreatedAtDesc(Collection<String> deviceIds, Pageable pageable);

    Page<ShoppingSession> findByDeviceIdInAndStateOrderByCreatedAtDesc(
            Collection<String> deviceIds, SessionState state, Pageable pageable);

    long countByDeviceIdIn(Collection<String> deviceIds);

    long countByDeviceIdInAndStateIn(Collection<String> deviceIds, List<SessionState> states);

    long countByDeviceIdInAndCreatedAtAfter(Collection<String> deviceIds, java.time.Instant since);

    long countByDeviceIdInAndState(Collection<String> deviceIds, SessionState state);

    long countByDeviceIdInAndStateAndUpdatedAtAfter(
            Collection<String> deviceIds, SessionState state, java.time.Instant since);

    @Query("""
            SELECT s FROM ShoppingSession s
            WHERE s.state IN :states AND s.updatedAt >= :since
            """)
    List<ShoppingSession> findByStateInAndUpdatedAtAfter(
            @Param("states") Collection<SessionState> states,
            @Param("since") java.time.Instant since);
}
