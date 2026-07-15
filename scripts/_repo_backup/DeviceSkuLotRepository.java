package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DeviceSkuLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DeviceSkuLotRepository extends JpaRepository<DeviceSkuLot, String> {

    List<DeviceSkuLot> findByDeviceIdAndSkuIdOrderByExpiryDateAsc(String deviceId, String skuId);

    List<DeviceSkuLot> findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc(
            String deviceId, String skuId, String slotId);

    List<DeviceSkuLot> findByDeviceId(String deviceId);

    Optional<DeviceSkuLot> findByDeviceIdAndSkuIdAndBatchNo(String deviceId, String skuId, String batchNo);

    @Query("""
            SELECT COALESCE(SUM(l.quantity), 0) FROM DeviceSkuLot l
            WHERE l.deviceId = :deviceId AND l.skuId = :skuId
              AND l.status IN ('ON_SALE', 'NEAR_EXPIRY')
            """)
    int sumSellableQuantity(@Param("deviceId") String deviceId, @Param("skuId") String skuId);

    @Query("""
            SELECT COUNT(l) FROM DeviceSkuLot l
            WHERE l.status IN ('ON_SALE', 'NEAR_EXPIRY')
              AND l.quantity > 0
              AND l.expiryDate <= :nearDate
              AND l.expiryDate > :today
            """)
    long countNearExpiry(@Param("today") LocalDate today, @Param("nearDate") LocalDate nearDate);

    @Query("""
            SELECT COUNT(l) FROM DeviceSkuLot l
            WHERE l.status IN ('ON_SALE', 'NEAR_EXPIRY', 'BLOCKED')
              AND l.quantity > 0
              AND l.expiryDate < :today
            """)
    long countExpiredWithStock(@Param("today") LocalDate today);

    List<DeviceSkuLot> findByStatusInAndQuantityGreaterThan(List<String> statuses, int quantity);

    @Query("""
            SELECT l.slotId, COALESCE(SUM(l.quantity), 0) FROM DeviceSkuLot l
            WHERE l.deviceId = :deviceId AND l.slotId IS NOT NULL AND l.slotId <> ''
              AND l.status IN ('ON_SALE', 'NEAR_EXPIRY') AND l.quantity > 0
            GROUP BY l.slotId
            """)
    List<Object[]> sumBookQtyBySlot(@Param("deviceId") String deviceId);
}
