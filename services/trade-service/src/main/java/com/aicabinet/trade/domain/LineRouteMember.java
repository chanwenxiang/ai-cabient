package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/** line_route_member（V358 线路组织）。 */
@TableName("line_route_member")
public class LineRouteMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long routeId;
    private Long userId;
    private String role;
    private String commissionBasis;
    private Integer commissionRateBps;
    private Long commissionPerUnitCents;
    private String status;
    private Instant createdAt;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long v) { this.routeId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getCommissionBasis() { return commissionBasis; }
    public void setCommissionBasis(String v) { this.commissionBasis = v; }
    public Integer getCommissionRateBps() { return commissionRateBps; }
    public void setCommissionRateBps(Integer v) { this.commissionRateBps = v; }
    public Long getCommissionPerUnitCents() { return commissionPerUnitCents; }
    public void setCommissionPerUnitCents(Long v) { this.commissionPerUnitCents = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
