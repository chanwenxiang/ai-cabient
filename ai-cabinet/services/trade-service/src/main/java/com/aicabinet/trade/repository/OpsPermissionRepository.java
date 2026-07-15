package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.OpsPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface OpsPermissionRepository extends JpaRepository<OpsPermission, Long> {

    @Query(value = """
            SELECT DISTINCT p.perm_code FROM ops_permission p
            JOIN ops_role_permission rp ON rp.permission_id = p.permission_id
            JOIN ops_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = :userId AND p.status = 'ACTIVE'
            """, nativeQuery = true)
    Set<String> findPermCodesByUserId(@Param("userId") Long userId);

    List<OpsPermission> findByStatusOrderBySortOrderAsc(String status);
}
