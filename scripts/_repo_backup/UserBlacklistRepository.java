package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.UserBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBlacklistRepository extends JpaRepository<UserBlacklist, Long> {
}
