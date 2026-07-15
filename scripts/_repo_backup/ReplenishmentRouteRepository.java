package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.ReplenishmentRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplenishmentRouteRepository extends JpaRepository<ReplenishmentRoute, Long> {
    List<ReplenishmentRoute> findAllByOrderByPlannedDateDesc();
}
