package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    List<InventoryMovement> findByDeviceIdOrderByCreatedAtDesc(String deviceId);
}
