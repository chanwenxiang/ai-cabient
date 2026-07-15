package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DataConsistencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface DataConsistencyRecordRepository extends JpaRepository<DataConsistencyRecord, Long> {
    List<DataConsistencyRecord> findByStatus(String status);
    
    List<DataConsistencyRecord> findByTableName(String tableName);
    
    @Query("SELECT r FROM DataConsistencyRecord r WHERE r.checkedAt >= :start AND r.checkedAt < :end")
    List<DataConsistencyRecord> findByCheckedAtBetween(Instant start, Instant end);
}
