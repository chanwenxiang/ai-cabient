package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import java.time.LocalDate;

@TableName(value = "payment_reconciliation", autoResultMap = true)
public class PaymentReconciliation {

    @TableId(type = IdType.AUTO)
    private Long reconId;

    private LocalDate reconDate;

    private String channel;

    private long platformTotal;

    private long ledgerTotal;

    private long diffCents;

    private int matchedCount;

    private int unmatchedCount;

    private String status = "PENDING";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String detail;

    private Instant createdAt;

    private Instant completedAt;

public Long getReconId() { return reconId; }
    public void setReconId(Long reconId) { this.reconId = reconId; }
    public LocalDate getReconDate() { return reconDate; }
    public void setReconDate(LocalDate reconDate) { this.reconDate = reconDate; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public long getPlatformTotal() { return platformTotal; }
    public void setPlatformTotal(long platformTotal) { this.platformTotal = platformTotal; }
    public long getLedgerTotal() { return ledgerTotal; }
    public void setLedgerTotal(long ledgerTotal) { this.ledgerTotal = ledgerTotal; }
    public long getDiffCents() { return diffCents; }
    public void setDiffCents(long diffCents) { this.diffCents = diffCents; }
    public int getMatchedCount() { return matchedCount; }
    public void setMatchedCount(int matchedCount) { this.matchedCount = matchedCount; }
    public int getUnmatchedCount() { return unmatchedCount; }
    public void setUnmatchedCount(int unmatchedCount) { this.unmatchedCount = unmatchedCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
