package com.aicabinet.trade.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "ops_user_role")
public class OpsUserRole {

    @EmbeddedId
    private OpsUserRoleId id;

    public OpsUserRole() {}

    public OpsUserRole(Long userId, Long roleId) {
        this.id = new OpsUserRoleId(userId, roleId);
    }

    public OpsUserRoleId getId() { return id; }
    public void setId(OpsUserRoleId id) { this.id = id; }
}
