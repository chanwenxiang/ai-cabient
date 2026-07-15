package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.WarehouseOutboundLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseOutboundLineRepository extends JpaRepository<WarehouseOutboundLine, Long> {

    List<WarehouseOutboundLine> findByOutboundIdOrderByLineIdAsc(Long outboundId);

    List<WarehouseOutboundLine> findByOutboundIdAndDeviceIdOrderByLineIdAsc(Long outboundId, String deviceId);
}
