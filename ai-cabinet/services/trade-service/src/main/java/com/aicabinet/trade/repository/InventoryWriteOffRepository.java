package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.InventoryWriteOff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InventoryWriteOffRepository extends JpaRepository<InventoryWriteOff, Long> {

    @Query("select coalesce(sum(w.costCents), 0) from InventoryWriteOff w where w.createdAt >= :since")
    long sumCostCentsSince(@Param("since") Instant since);

    @Query("select coalesce(sum(w.quantity), 0) from InventoryWriteOff w where w.createdAt >= :since")
    long sumQuantitySince(@Param("since") Instant since);

    @Query("select coalesce(sum(w.costCents), 0) from InventoryWriteOff w "
            + "where w.createdAt >= :start and w.createdAt < :end")
    long sumCostCentsBetween(@Param("start") Instant start, @Param("end") Instant end);
}
