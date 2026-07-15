package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.WarehouseInTransit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseInTransitRepository extends JpaRepository<WarehouseInTransit, Long> {

    List<WarehouseInTransit> findByDeviceIdAndStatus(String deviceId, String status);

    List<WarehouseInTransit> findByOutboundIdAndDeviceIdAndStatus(Long outboundId, String deviceId, String status);

    List<WarehouseInTransit> findByStatusOrderByCreatedAtAsc(String status);
}
