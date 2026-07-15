package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.WarehouseOutboundLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarehouseOutboundLineRepository extends JpaRepository<WarehouseOutboundLine, Long> {

    List<WarehouseOutboundLine> findByOutboundIdOrderByLineIdAsc(Long outboundId);

    List<WarehouseOutboundLine> findByOutboundIdAndDeviceIdOrderByLineIdAsc(Long outboundId, String deviceId);

    @Query("""
            SELECT COALESCE(SUM(l.quantity), 0)
            FROM WarehouseOutboundLine l
            JOIN WarehouseOutbound o ON o.outboundId = l.outboundId
            WHERE o.warehouseId = :warehouseId
              AND l.skuId = :skuId
              AND l.batchNo = :batchNo
              AND o.status IN ('DRAFT', 'PICKED')
            """)
    int sumAllocatedQty(@Param("warehouseId") String warehouseId,
                        @Param("skuId") String skuId,
                        @Param("batchNo") String batchNo);
}
