package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserCheckinRepository extends JpaRepository<UserCheckin, Long> {
    Optional<UserCheckin> findByUserIdAndCheckinDate(Long userId, LocalDate checkinDate);
    
    List<UserCheckin> findByUserIdOrderByCheckinDateDesc(Long userId);
    
    Optional<UserCheckin> findFirstByUserIdOrderByCheckinDateDesc(Long userId);
}
