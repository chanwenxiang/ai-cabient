package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsRolePermission;
import com.aicabinet.trade.domain.OpsRolePermissionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OpsRolePermissionRepository extends JpaRepository<OpsRolePermission, OpsRolePermissionId> {

    List<OpsRolePermission> findByIdRoleId(Long roleId);

    void deleteByIdRoleId(Long roleId);

    @Query("SELECT rp.id.permissionId FROM OpsRolePermission rp WHERE rp.id.roleId = :roleId")
    List<Long> findPermissionIdsByRoleId(@Param("roleId") Long roleId);
}
