package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.GameTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GameTaskRepository extends JpaRepository<GameTask, Long> {
    Optional<GameTask> findByTaskCode(String taskCode);
    
    List<GameTask> findByStatus(String status);
    
    List<GameTask> findByTaskType(String taskType);
}
