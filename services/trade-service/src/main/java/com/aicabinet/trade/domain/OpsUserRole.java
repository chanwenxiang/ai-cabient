package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;

@TableName("ops_user_role")
public class OpsUserRole {

    @TableField(exist = false)
    private OpsUserRoleId id;

    private Long userId;

    private Long roleId;

    public OpsUserRole() {}

    public OpsUserRole(Long userId, Long roleId) {
        setId(new OpsUserRoleId(userId, roleId));
    }

    public OpsUserRoleId getId() {
        if (id == null && userId != null && roleId != null) {
            id = new OpsUserRoleId(userId, roleId);
        }
        return id;
    }
    public void setId(OpsUserRoleId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
            this.roleId = id.getRoleId();
        }
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
}
