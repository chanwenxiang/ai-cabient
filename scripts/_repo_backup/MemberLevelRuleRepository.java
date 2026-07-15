package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.MemberLevelRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MemberLevelRuleRepository extends JpaRepository<MemberLevelRule, Long> {
    Optional<MemberLevelRule> findByLevelCode(String levelCode);
    
    List<MemberLevelRule> findByStatus(String status);
    
    List<MemberLevelRule> findByStatusOrderBySortorderAsc(String status);
}
