package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.LineDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface LineDeviceRepository extends JpaRepository<LineDevice, Long> {
    List<LineDevice> findByManagerId(Long managerId);
    
    Optional<LineDevice> findByDeviceIdAndStatus(String deviceId, String status);
    
    List<LineDevice> findByStatus(String status);
}
