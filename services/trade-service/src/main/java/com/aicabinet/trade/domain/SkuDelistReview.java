package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("sku_delist_review")
public class SkuDelistReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuId;

    private String reviewStatus = "PENDING";

    private String performanceLevel;

    private Integer salesQty = 0;

    private Long revenueCents = 0L;

    private Integer stockDays;

    private String actionType;

    private String reason;

    private String replaceSkuId;

    private Long reviewedBy;

    private Instant reviewedAt;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getPerformanceLevel() { return performanceLevel; }
    public void setPerformanceLevel(String performanceLevel) { this.performanceLevel = performanceLevel; }
    public Integer getSalesQty() { return salesQty; }
    public void setSalesQty(Integer salesQty) { this.salesQty = salesQty; }
    public Long getRevenueCents() { return revenueCents; }
    public void setRevenueCents(Long revenueCents) { this.revenueCents = revenueCents; }
    public Integer getStockDays() { return stockDays; }
    public void setStockDays(Integer stockDays) { this.stockDays = stockDays; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getReplaceSkuId() { return replaceSkuId; }
    public void setReplaceSkuId(String replaceSkuId) { this.replaceSkuId = replaceSkuId; }
    public Long getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(Long reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
