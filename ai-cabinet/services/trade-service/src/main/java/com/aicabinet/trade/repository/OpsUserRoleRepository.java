package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsUserRole;
import com.aicabinet.trade.domain.OpsUserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpsUserRoleRepository extends JpaRepository<OpsUserRole, OpsUserRoleId> {
    List<OpsUserRole> findByIdUserId(Long userId);
    void deleteByIdUserId(Long userId);
}
