package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.AdCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface AdCampaignRepository extends JpaRepository<AdCampaign, Long> {
    List<AdCampaign> findBySlotIdAndStatus(Long slotId, String status);
    
    List<AdCampaign> findByStatus(String status);
    
    @Query("SELECT c FROM AdCampaign c WHERE c.slotId = :slotId AND c.status = 'ACTIVE' AND c.startTime <= :now AND (c.endTime IS NULL OR c.endTime > :now) ORDER BY c.priority DESC")
    List<AdCampaign> findActiveCampaigns(Long slotId, Instant now);
}
