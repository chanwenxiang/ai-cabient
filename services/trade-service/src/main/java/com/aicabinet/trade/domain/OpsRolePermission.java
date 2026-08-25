package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_role_permission")
@Getter
@Setter
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

}
