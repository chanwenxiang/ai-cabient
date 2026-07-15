package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.MemberPointsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface MemberPointsLogRepository extends JpaRepository<MemberPointsLog, Long> {
    List<MemberPointsLog> findByMemberId(Long memberId);
    
    List<MemberPointsLog> findByMemberIdAndPointsType(Long memberId, String pointsType);
    
    @Query("SELECT l FROM MemberPointsLog l WHERE l.memberId = :memberId AND l.expireAt IS NOT NULL AND l.expireAt < :now")
    List<MemberPointsLog> findExpiredPoints(Long memberId, Instant now);
}
