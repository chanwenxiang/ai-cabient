package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

@TableName("finance_margin_daily_lock")
public class FinanceMarginDailyLock {
    @TableId(type = IdType.INPUT)
    private LocalDate bizDate;
    private long revenueCents;
    private long cogsCents;
    private long marginCents;
    private long writeOffCents;
    private long orderCount;
    private Instant lockedAt;
    private Long lockedBy;

    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public long getRevenueCents() { return revenueCents; }
    public void setRevenueCents(long revenueCents) { this.revenueCents = revenueCents; }
    public long getCogsCents() { return cogsCents; }
    public void setCogsCents(long cogsCents) { this.cogsCents = cogsCents; }
    public long getMarginCents() { return marginCents; }
    public void setMarginCents(long marginCents) { this.marginCents = marginCents; }
    public long getWriteOffCents() { return writeOffCents; }
    public void setWriteOffCents(long writeOffCents) { this.writeOffCents = writeOffCents; }
    public long getOrderCount() { return orderCount; }
    public void setOrderCount(long orderCount) { this.orderCount = orderCount; }
    public Instant getLockedAt() { return lockedAt; }
    public void setLockedAt(Instant lockedAt) { this.lockedAt = lockedAt; }
    public Long getLockedBy() { return lockedBy; }
    public void setLockedBy(Long lockedBy) { this.lockedBy = lockedBy; }
}
