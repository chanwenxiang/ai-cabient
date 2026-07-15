package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.ShareReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ShareRewardRepository extends JpaRepository<ShareReward, Long> {
    List<ShareReward> findBySharerId(Long sharerId);
    
    List<ShareReward> findByInviteeId(Long inviteeId);
    
    List<ShareReward> findByStatus(String status);
}
