package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.CompensationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface CompensationTaskRepository extends JpaRepository<CompensationTask, Long> {
    List<CompensationTask> findByStatusAndScheduledAtBefore(String status, Instant scheduledAt);
    
    @Query("SELECT c FROM CompensationTask c WHERE c.status = 'PENDING' AND c.scheduledAt <= :now ORDER BY c.priority DESC")
    List<CompensationTask> findExecutableTasks(Instant now);
}
