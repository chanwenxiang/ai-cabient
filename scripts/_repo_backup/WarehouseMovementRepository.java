package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.WarehouseMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseMovementRepository extends JpaRepository<WarehouseMovement, Long> {
    List<WarehouseMovement> findTop100ByWarehouseIdOrderByCreatedAtDesc(String warehouseId);
}
