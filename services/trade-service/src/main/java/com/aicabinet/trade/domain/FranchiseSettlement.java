package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("franchise_settlement")
public class FranchiseSettlement {
    @TableId(type = IdType.AUTO)
    private Long settlementId;

    
    private Long franchiseId;
    
    private String settlementPeriod;
    
    private java.math.BigDecimal grossRevenue;
    
    private java.math.BigDecimal commissionAmount;
    
    private java.math.BigDecimal adjustmentAmount;
    
    private java.math.BigDecimal netAmount;
    
    private String status;
    
    private Instant createdAt = Instant.now();
    
    private Instant settledAt;
    
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    
    public Long getFranchiseId() { return franchiseId; }
    public void setFranchiseId(Long franchiseId) { this.franchiseId = franchiseId; }
    
    public String getSettlementPeriod() { return settlementPeriod; }
    public void setSettlementPeriod(String settlementPeriod) { this.settlementPeriod = settlementPeriod; }
    
    public java.math.BigDecimal getGrossRevenue() { return grossRevenue; }
    public void setGrossRevenue(java.math.BigDecimal grossRevenue) { this.grossRevenue = grossRevenue; }
    
    public java.math.BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(java.math.BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    
    public java.math.BigDecimal getAdjustmentAmount() { return adjustmentAmount; }
    public void setAdjustmentAmount(java.math.BigDecimal adjustmentAmount) { this.adjustmentAmount = adjustmentAmount; }
    
    public java.math.BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(java.math.BigDecimal netAmount) { this.netAmount = netAmount; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
}
