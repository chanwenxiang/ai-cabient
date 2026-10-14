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
    /** GMV 模式发佣时该柜当日 GMV 基数（分）；RESTOCK_QTY 模式为 0。V361。 */
    private Long gmvCents;
    /** 发佣时使用的模式（GMV / RESTOCK_QTY）——冲回时按此判定（费率可能已变）。V361。 */
    private String commissionBasis;
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
    public Long getGmvCents() { return gmvCents; }
    public void setGmvCents(Long v) { this.gmvCents = v; }
    public String getCommissionBasis() { return commissionBasis; }
    public void setCommissionBasis(String v) { this.commissionBasis = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
