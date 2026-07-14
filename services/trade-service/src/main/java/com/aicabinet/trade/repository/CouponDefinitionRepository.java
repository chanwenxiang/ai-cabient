package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.CouponDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CouponDefinitionRepository extends JpaRepository<CouponDefinition, Long> {
    List<CouponDefinition> findByStatus(String status);
    List<CouponDefinition> findByActivityId(Long activityId);
    long countByStatus(String status);
}
