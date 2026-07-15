package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByUserId(Long userId);
    
    Optional<Member> findByInviteCode(String inviteCode);
    
    List<Member> findByMemberLevel(String memberLevel);
    
    List<Member> findByInvitedBy(Long invitedBy);
    
    @Query("SELECT m FROM Member m WHERE m.totalSpent >= :minSpent ORDER BY m.totalSpent DESC")
    List<Member> findByTotalSpentGreaterThanEqual(java.math.BigDecimal minSpent);
}
