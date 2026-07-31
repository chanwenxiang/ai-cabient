package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("line_manager")
public class LineManager {

    @TableId(type = IdType.AUTO)
    private Long managerId;
    private String managerName;
    private String phone;
    private String status;
    private String wxOpenid;
    private Long userId;
    private String orgName;
    private Integer commissionRateBps;
    private Integer commissionFixedCents;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public String getManagerName() { return managerName; }
    public void setManagerName(String managerName) { this.managerName = managerName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWxOpenid() { return wxOpenid; }
    public void setWxOpenid(String wxOpenid) { this.wxOpenid = wxOpenid; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public Integer getCommissionRateBps() { return commissionRateBps; }
    public void setCommissionRateBps(Integer commissionRateBps) { this.commissionRateBps = commissionRateBps; }
    public Integer getCommissionFixedCents() { return commissionFixedCents; }
    public void setCommissionFixedCents(Integer commissionFixedCents) { this.commissionFixedCents = commissionFixedCents; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
