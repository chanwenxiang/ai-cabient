package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM UserAccount a WHERE a.userId = :userId")
    Optional<UserAccount> findByIdForUpdate(@Param("userId") Long userId);
}
