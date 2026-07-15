package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.RedPacketClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RedPacketClaimRepository extends JpaRepository<RedPacketClaim, Long> {
    List<RedPacketClaim> findByPacketId(Long packetId);
    
    List<RedPacketClaim> findByUserId(Long userId);
    
    Optional<RedPacketClaim> findByPacketIdAndUserId(Long packetId, Long userId);
}
