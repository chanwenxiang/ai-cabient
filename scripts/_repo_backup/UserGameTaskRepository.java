package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserGameTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserGameTaskRepository extends JpaRepository<UserGameTask, Long> {
    List<UserGameTask> findByUserId(Long userId);
    
    Optional<UserGameTask> findByUserIdAndTaskId(Long userId, Long taskId);
    
    List<UserGameTask> findByUserIdAndStatus(Long userId, String status);
}
