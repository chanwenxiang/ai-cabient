package com.aicabinet.trade.domain;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OpsRolePermissionId implements Serializable {
    private Long roleId;
    private Long permissionId;

    public OpsRolePermissionId() {}

    public OpsRolePermissionId(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpsRolePermissionId that)) return false;
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
