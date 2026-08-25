package com.aicabinet.trade.domain;

import java.io.Serializable;
import java.util.Objects;

public class OpsUserDepartmentId implements Serializable {
    private Long userId;
    private Long deptId;

    public OpsUserDepartmentId() {}

    public OpsUserDepartmentId(Long userId, Long deptId) {
        this.userId = userId;
        this.deptId = deptId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpsUserDepartmentId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(deptId, that.deptId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, deptId);
    }
}
