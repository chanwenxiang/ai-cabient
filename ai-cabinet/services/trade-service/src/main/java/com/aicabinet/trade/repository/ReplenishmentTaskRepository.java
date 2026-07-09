package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.ReplenishmentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, Long> {
    List<ReplenishmentTask> findByRouteId(Long routeId);

    List<ReplenishmentTask> findByOutboundId(Long outboundId);

    List<ReplenishmentTask> findByDeviceIdAndStatusIn(String deviceId, List<String> statuses);
    List<ReplenishmentTask> findByAssigneeUserIdAndStatus(Long assigneeUserId, String status);

    List<ReplenishmentTask> findByAssigneeUserIdAndStatusIn(Long assigneeUserId, List<String> statuses);

    @Query("""
            SELECT MAX(t.completedAt) FROM ReplenishmentTask t
            WHERE t.deviceId = :deviceId AND t.status = 'COMPLETED'
            """)
    java.util.Optional<java.time.Instant> findLastCompletedAtByDeviceId(@Param("deviceId") String deviceId);
}
