package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.RedPacket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RedPacketRepository extends JpaRepository<RedPacket, Long> {
    Optional<RedPacket> findByPacketCode(String packetCode);
    
    List<RedPacket> findBySenderId(Long senderId);
    
    List<RedPacket> findByStatus(String status);
}
