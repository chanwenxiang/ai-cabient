package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.DistributedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface DistributedTransactionRepository extends JpaRepository<DistributedTransaction, String> {
    List<DistributedTransaction> findByStatusAndCreatedAtBefore(String status, Instant createdAt);
    
    List<DistributedTransaction> findByStatus(String status);
    
    @Query("SELECT t FROM DistributedTransaction t WHERE t.status = 'PENDING' AND t.retryCount < t.maxRetry")
    List<DistributedTransaction> findRetryableTransactions();
}
