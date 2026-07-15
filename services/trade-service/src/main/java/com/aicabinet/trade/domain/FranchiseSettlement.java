package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "franchise_settlement")
public class FranchiseSettlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long settlementId;
    
    @Column(nullable = false)
    private Long franchiseId;
    
    @Column(length = 32, nullable = false)
    private String settlementPeriod;
    
    @Column(precision = 12, scale = 2, nullable = false)
    private java.math.BigDecimal grossRevenue;
    
    @Column(precision = 12, scale = 2, nullable = false)
    private java.math.BigDecimal commissionAmount;
    
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal adjustmentAmount;
    
    @Column(precision = 12, scale = 2, nullable = false)
    private java.math.BigDecimal netAmount;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
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
