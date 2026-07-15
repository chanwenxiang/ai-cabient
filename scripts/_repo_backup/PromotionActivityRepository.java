package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.PromotionActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface PromotionActivityRepository extends JpaRepository<PromotionActivity, Long> {
    List<PromotionActivity> findByStatus(String status);
    List<PromotionActivity> findByStatusAndStartTimeBeforeAndEndTimeAfter(String status, Instant now1, Instant now2);
    List<PromotionActivity> findByActivityType(String activityType);
}
