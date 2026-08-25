package com.aicabinet.trade.domain;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OpsUserRoleId implements Serializable {
    private Long userId;
    private Long roleId;

    public OpsUserRoleId() {}

    public OpsUserRoleId(Long userId, Long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpsUserRoleId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(roleId, that.roleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roleId);
    }
}
