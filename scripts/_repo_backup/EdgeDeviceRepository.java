package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.EdgeDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EdgeDeviceRepository extends JpaRepository<EdgeDevice, Long> {
    Optional<EdgeDevice> findByDeviceId(String deviceId);
    
    List<EdgeDevice> findByStatus(String status);
}
