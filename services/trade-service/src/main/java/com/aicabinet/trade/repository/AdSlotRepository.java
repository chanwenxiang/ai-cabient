package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.AdSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdSlotRepository extends JpaRepository<AdSlot, Long> {
    Optional<AdSlot> findBySlotCode(String slotCode);
    
    List<AdSlot> findByStatus(String status);
    
    List<AdSlot> findByDeviceId(String deviceId);
}
