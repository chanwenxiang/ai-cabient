package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepository extends JpaRepository<UserInfo, Long> {

    Optional<UserInfo> findByPhoneNumber(String phoneNumber);

    Optional<UserInfo> findByWxOpenId(String wxOpenId);

    Page<UserInfo> findAllByOrderByUserIdDesc(Pageable pageable);

    Page<UserInfo> findByPhoneNumberContainingOrderByUserIdDesc(String phoneNumber, Pageable pageable);

    @Query("""
            SELECT u FROM UserInfo u WHERE
            (:phone IS NULL OR :phone = '' OR u.phoneNumber LIKE CONCAT('%', :phone, '%')) AND
            (:name IS NULL OR :name = '' OR COALESCE(u.name, '') LIKE CONCAT('%', :name, '%')) AND
            (:verified IS NULL OR u.verified = :verified) AND
            (:minUserId IS NULL OR u.userId >= :minUserId) AND
            (:maxUserId IS NULL OR u.userId <= :maxUserId)
            ORDER BY u.userId DESC
            """)
    Page<UserInfo> searchForAdmin(@Param("phone") String phone,
                                  @Param("name") String name,
                                  @Param("verified") Boolean verified,
                                  @Param("minUserId") Long minUserId,
                                  @Param("maxUserId") Long maxUserId,
                                  Pageable pageable);

    Page<UserInfo> findByUserIdGreaterThanEqualOrderByUserIdDesc(Long userId, Pageable pageable);

    Page<UserInfo> findByUserIdGreaterThanEqualAndPhoneNumberContainingOrderByUserIdDesc(
            Long userId, String phoneNumber, Pageable pageable);

    List<UserInfo> findByUserIdIn(List<Long> userIds);
}
