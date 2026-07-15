package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.LineManagerSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface LineManagerSettlementRepository extends JpaRepository<LineManagerSettlement, Long> {
    List<LineManagerSettlement> findByManagerId(Long managerId);
    
    Optional<LineManagerSettlement> findByManagerIdAndSettlementPeriod(Long managerId, String settlementPeriod);
    
    List<LineManagerSettlement> findByStatus(String status);
}
