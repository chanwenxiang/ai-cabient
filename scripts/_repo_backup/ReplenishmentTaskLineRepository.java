package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReplenishmentTaskLineRepository extends JpaRepository<ReplenishmentTaskLine, Long> {

    List<ReplenishmentTaskLine> findByTaskIdOrderByLineIdAsc(Long taskId);

    List<ReplenishmentTaskLine> findByTaskIdAndAppliedFalse(Long taskId);

    void deleteByTaskIdAndAppliedFalse(Long taskId);
}
