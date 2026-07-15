package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OpsRoleRepository extends JpaRepository<OpsRole, Long> {
    Optional<OpsRole> findByRoleKey(String roleKey);
}
