package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("points_redeem_item")
public class PointsRedeemItem {

    @TableId(type = IdType.AUTO)
    private Long itemId;

    private String title;

    private String subtitle;

    private String coverEmoji = "馃巵";

    private Integer pointsCost;

    private Long couponDefId;

    private Integer stockTotal = 0;

    private Integer redeemedCount = 0;

    private Integer sortOrder = 0;

    private String status = "ACTIVE";

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getCoverEmoji() { return coverEmoji; }
    public void setCoverEmoji(String coverEmoji) { this.coverEmoji = coverEmoji; }
    public Integer getPointsCost() { return pointsCost; }
    public void setPointsCost(Integer pointsCost) { this.pointsCost = pointsCost; }
    public Long getCouponDefId() { return couponDefId; }
    public void setCouponDefId(Long couponDefId) { this.couponDefId = couponDefId; }
    public Integer getStockTotal() { return stockTotal; }
    public void setStockTotal(Integer stockTotal) { this.stockTotal = stockTotal; }
    public Integer getRedeemedCount() { return redeemedCount; }
    public void setRedeemedCount(Integer redeemedCount) { this.redeemedCount = redeemedCount; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
