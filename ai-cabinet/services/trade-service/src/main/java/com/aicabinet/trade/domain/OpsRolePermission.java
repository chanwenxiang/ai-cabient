package com.aicabinet.trade.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "ops_role_permission")
public class OpsRolePermission {

    @EmbeddedId
    private OpsRolePermissionId id;

    public OpsRolePermission() {}

    public OpsRolePermission(Long roleId, Long permissionId) {
        this.id = new OpsRolePermissionId(roleId, permissionId);
    }

    public OpsRolePermissionId getId() { return id; }
    public void setId(OpsRolePermissionId id) { this.id = id; }
}
