package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.LineManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface LineManagerRepository extends JpaRepository<LineManager, Long> {
    Optional<LineManager> findByPhone(String phone);
    
    Optional<LineManager> findByEmployeeId(String employeeId);
    
    List<LineManager> findByFranchiseId(Long franchiseId);
    
    List<LineManager> findByStatus(String status);
}
