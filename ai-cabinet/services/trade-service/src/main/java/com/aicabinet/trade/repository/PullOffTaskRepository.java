package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.PullOffTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PullOffTaskRepository extends JpaRepository<PullOffTask, Long> {

    long countByStatus(String status);

    List<PullOffTask> findByStatusOrderByCreatedAtDesc(String status);

    Optional<PullOffTask> findByLotIdAndStatus(String lotId, String status);
}
