package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DataChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface DataChangeLogRepository extends JpaRepository<DataChangeLog, Long> {
    List<DataChangeLog> findByTableNameAndRecordId(String tableName, String recordId);
    
    List<DataChangeLog> findByVerifiedFalse();
    
    @Query("SELECT l FROM DataChangeLog l WHERE l.changedAt >= :start AND l.changedAt < :end")
    List<DataChangeLog> findByChangedAtBetween(Instant start, Instant end);
}