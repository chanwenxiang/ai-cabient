package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.FranchiseSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FranchiseSettlementRepository extends JpaRepository<FranchiseSettlement, Long> {
    List<FranchiseSettlement> findByFranchiseId(Long franchiseId);
    
    Optional<FranchiseSettlement> findByFranchiseIdAndSettlementPeriod(Long franchiseId, String settlementPeriod);
    
    List<FranchiseSettlement> findByStatus(String status);
}
