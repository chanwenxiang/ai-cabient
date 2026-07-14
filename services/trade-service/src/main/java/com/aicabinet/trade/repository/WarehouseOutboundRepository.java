package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.WarehouseOutbound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseOutboundRepository extends JpaRepository<WarehouseOutbound, Long> {

    List<WarehouseOutbound> findAllByOrderByCreatedAtDesc();

    Optional<WarehouseOutbound> findByRouteId(Long routeId);
}
