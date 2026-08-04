package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;

@TableName("ops_role_permission")
public class OpsRolePermission {

    @TableField(exist = false)
    private OpsRolePermissionId id;

    private Long roleId;

    private Long permissionId;

    public OpsRolePermission() {}

    public OpsRolePermission(Long roleId, Long permissionId) {
        setId(new OpsRolePermissionId(roleId, permissionId));
    }

    public OpsRolePermissionId getId() {
        if (id == null && roleId != null && permissionId != null) {
            id = new OpsRolePermissionId(roleId, permissionId);
        }
        return id;
    }
    public void setId(OpsRolePermissionId id) {
        this.id = id;
        if (id != null) {
            this.roleId = id.getRoleId();
            this.permissionId = id.getPermissionId();
        }
    }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Long getPermissionId() { return permissionId; }
    public void setPermissionId(Long permissionId) { this.permissionId = permissionId; }
}
