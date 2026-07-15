package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {
    List<UserCoupon> findByUserIdAndStatus(Long userId, String status);
    List<UserCoupon> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<UserCoupon> findByCouponCode(String couponCode);
    long countByUserIdAndStatus(Long userId, String status);
    List<UserCoupon> findByStatusAndExpireAtBefore(String status, Instant now);
    long countByCouponDefId(Long couponDefId);
}
