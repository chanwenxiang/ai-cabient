package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {}
