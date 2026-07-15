package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expireAt < :now")
    int deleteExpiredKeys(@Param("now") Instant now);
    
    IdempotencyKey findByBusinessTypeAndBusinessId(String businessType, String businessId);
}