package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.Achievement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AchievementRepository extends JpaRepository<Achievement, Long> {
    Optional<Achievement> findByAchievementCode(String achievementCode);
    
    List<Achievement> findByStatus(String status);
    
    List<Achievement> findByCategory(String category);
}
