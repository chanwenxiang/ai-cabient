package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.CabinetOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CabinetOrderLineRepository extends JpaRepository<CabinetOrderLine, Long> {

    @Query("""
            SELECT COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.createdAt >= :since
            """)
    long sumCogsSince(@Param("since") Instant since);

    @Query("""
            SELECT COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l
            """)
    long sumCogsTotal();

    @Query("""
            SELECT l.skuId, COALESCE(SUM(l.quantity), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.deviceId = :deviceId AND o.createdAt >= :since
            GROUP BY l.skuId
            """)
    List<Object[]> sumSoldQtyBySkuSince(@Param("deviceId") String deviceId, @Param("since") Instant since);

    @Query("""
            SELECT COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.createdAt >= :start AND o.createdAt < :end
            """)
    long sumCogsBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            SELECT l.skuId, l.skuName,
                   COALESCE(SUM(l.quantity), 0),
                   COALESCE(SUM(l.lineAmountCents), 0),
                   COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.createdAt >= :since
            GROUP BY l.skuId, l.skuName
            ORDER BY SUM(l.lineAmountCents) DESC
            """)
    List<Object[]> skuBreakdownSince(@Param("since") Instant since);

    @Query("""
            SELECT l.skuId, l.skuName,
                   COALESCE(SUM(l.quantity), 0),
                   COALESCE(SUM(l.lineAmountCents), 0),
                   COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.deviceId IN :deviceIds AND o.createdAt >= :since
            GROUP BY l.skuId, l.skuName
            ORDER BY SUM(l.lineAmountCents) DESC
            """)
    List<Object[]> skuBreakdownByDevicesSince(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("since") Instant since);

    @Query("""
            SELECT COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.deviceId IN :deviceIds AND o.createdAt >= :since
            """)
    long sumCogsByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("since") Instant since);

    @Query("""
            SELECT COALESCE(SUM(l.lineAmountCents), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.deviceId IN :deviceIds AND o.createdAt >= :since
            """)
    long sumRevenueByDeviceIdsSince(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("since") Instant since);

    @Query("""
            SELECT COALESCE(SUM(l.quantity * COALESCE(l.unitCostCents, 0)), 0)
            FROM CabinetOrderLine l JOIN l.order o
            WHERE o.deviceId IN :deviceIds AND o.createdAt >= :start AND o.createdAt < :end
            """)
    long sumCogsByDeviceIdsBetween(
            @Param("deviceIds") Collection<String> deviceIds,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
