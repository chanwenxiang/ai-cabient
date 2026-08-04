package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/** Daily commission posting row for a line manager + device. */
@TableName("line_commission_daily")
public class LineCommissionDaily {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long managerId;
    private LocalDate bizDate;
    private String deviceId;
    private Integer orderCount;
    private Long gmvCents;
    private Long commissionCents;
    private String status;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public LocalDate getBizDate() {
        return bizDate;
    }

    public void setBizDate(LocalDate bizDate) {
        this.bizDate = bizDate;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Integer getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Integer orderCount) {
        this.orderCount = orderCount;
    }

    public Long getGmvCents() {
        return gmvCents;
    }

    public void setGmvCents(Long gmvCents) {
        this.gmvCents = gmvCents;
    }

    public Long getCommissionCents() {
        return commissionCents;
    }

    public void setCommissionCents(Long commissionCents) {
        this.commissionCents = commissionCents;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
