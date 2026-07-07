package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.ReplenishmentTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, Long> {
    List<ReplenishmentTask> findByRouteId(Long routeId);
    List<ReplenishmentTask> findByAssigneeUserIdAndStatus(Long assigneeUserId, String status);
}
