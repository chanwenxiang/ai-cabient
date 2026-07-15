package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.Franchise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FranchiseRepository extends JpaRepository<Franchise, Long> {
    Optional<Franchise> findByFranchiseCode(String franchiseCode);
    
    List<Franchise> findByStatus(String status);
    
    List<Franchise> findByProvinceAndCity(String province, String city);
    
    @Query("SELECT f FROM Franchise f WHERE f.contractEndDate >= CURRENT_TIMESTAMP AND f.status = 'ACTIVE'")
    List<Franchise> findActiveWithValidContract();
}
