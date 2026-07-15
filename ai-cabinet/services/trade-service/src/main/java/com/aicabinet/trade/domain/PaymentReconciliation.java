package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "payment_reconciliation")
public class PaymentReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long reconId;

    @Column(nullable = false)
    private LocalDate reconDate;

    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false)
    private long platformTotal;

    @Column(nullable = false)
    private long ledgerTotal;

    @Column(nullable = false)
    private long diffCents;

    @Column(nullable = false)
    private int matchedCount;

    @Column(nullable = false)
    private int unmatchedCount;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

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
