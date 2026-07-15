package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.GroupBuyParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface GroupBuyParticipantRepository extends JpaRepository<GroupBuyParticipant, Long> {
    List<GroupBuyParticipant> findByGroupBuyId(Long groupBuyId);
    
    List<GroupBuyParticipant> findByUserId(Long userId);
}
