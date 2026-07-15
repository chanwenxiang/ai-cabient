package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.AdImpression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface AdImpressionRepository extends JpaRepository<AdImpression, Long> {
    List<AdImpression> findByCampaignId(Long campaignId);
    
    List<AdImpression> findBySlotId(Long slotId);
    
    @Query("SELECT SUM(i.cost) FROM AdImpression i WHERE i.campaignId = :campaignId")
    java.math.BigDecimal sumCostByCampaignId(Long campaignId);
    
    @Query("SELECT COUNT(i) FROM AdImpression i WHERE i.campaignId = :campaignId AND i.eventType = :eventType")
    Long countByCampaignIdAndEventType(Long campaignId, String eventType);
}
