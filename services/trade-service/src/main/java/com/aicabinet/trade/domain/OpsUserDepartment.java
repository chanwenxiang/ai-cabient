package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_user_department")
@Getter
@Setter
public class OpsUserDepartment {

    @TableField(exist = false)
    private OpsUserDepartmentId id;

    private Long userId;
    private Long deptId;
    private Boolean isPrimary = false;

    public OpsUserDepartment() {}

    public OpsUserDepartment(Long userId, Long deptId) {
        setId(new OpsUserDepartmentId(userId, deptId));
        this.isPrimary = false;
    }

    public OpsUserDepartment(Long userId, Long deptId, boolean isPrimary) {
        setId(new OpsUserDepartmentId(userId, deptId));
        this.isPrimary = isPrimary;
    }

    public OpsUserDepartmentId getId() {
        if (id == null && userId != null && deptId != null) {
            id = new OpsUserDepartmentId(userId, deptId);
        }
        return id;
    }

    public void setId(OpsUserDepartmentId id) {
        this.id = id;
        if (id != null) {
            this.userId = id.getUserId();
            this.deptId = id.getDeptId();
        }
    }
}
