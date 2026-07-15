package com.aicabinet.trade.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "ops_permission")
public class OpsPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long permissionId;

    @Column(nullable = false)
    private Long parentId = 0L;

    @Column(nullable = false, unique = true, length = 128)
    private String permCode;

    @Column(nullable = false, length = 64)
    private String permName;

    @Column(nullable = false, length = 8)
    private String permType = "M";

    @Column(length = 128)
    private String path;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    public Long getPermissionId() { return permissionId; }
    public void setPermissionId(Long permissionId) { this.permissionId = permissionId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getPermCode() { return permCode; }
    public void setPermCode(String permCode) { this.permCode = permCode; }
    public String getPermName() { return permName; }
    public void setPermName(String permName) { this.permName = permName; }
    public String getPermType() { return permType; }
    public void setPermType(String permType) { this.permType = permType; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
