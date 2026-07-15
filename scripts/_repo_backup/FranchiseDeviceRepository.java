package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.FranchiseDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FranchiseDeviceRepository extends JpaRepository<FranchiseDevice, Long> {
    List<FranchiseDevice> findByFranchiseId(Long franchiseId);
    
    Optional<FranchiseDevice> findByDeviceIdAndStatus(String deviceId, String status);
    
    List<FranchiseDevice> findByStatus(String status);
}
