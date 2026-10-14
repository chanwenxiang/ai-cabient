package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/** staff_commission_daily（V358 线路组织）。 */
@TableName("staff_commission_daily")
public class StaffCommissionDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long staffUserId;
    private Long routeId;
    private LocalDate bizDate;
    private String deviceId;
    private Integer restockQty;
    private Long commissionCents;
    private String status;
    private Instant createdAt;
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public Long getStaffUserId() { return staffUserId; }
    public void setStaffUserId(Long v) { this.staffUserId = v; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long v) { this.routeId = v; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate v) { this.bizDate = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public Integer getRestockQty() { return restockQty; }
    public void setRestockQty(Integer v) { this.restockQty = v; }
    public Long getCommissionCents() { return commissionCents; }
    public void setCommissionCents(Long v) { this.commissionCents = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
