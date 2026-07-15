package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.GroupBuy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface GroupBuyRepository extends JpaRepository<GroupBuy, Long> {
    List<GroupBuy> findByStatus(String status);
    
    @Query("SELECT g FROM GroupBuy g WHERE g.status = 'ACTIVE' AND g.endTime > :now")
    List<GroupBuy> findActive(Instant now);
}
