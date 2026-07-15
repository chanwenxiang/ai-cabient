package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.TransactionStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransactionStepRepository extends JpaRepository<TransactionStep, Long> {
    List<TransactionStep> findByTxIdOrderByStepOrder(String txId);
    
    List<TransactionStep> findByTxIdAndStatus(String txId, String status);
}
