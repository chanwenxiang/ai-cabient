package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchant_notify_log")
public class MerchantNotifyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long logId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String digest;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    void prePersist() {
        if (sentAt == null) {
            sentAt = Instant.now();
        }
    }

    public Long getLogId() { return logId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDigest() { return digest; }
    public void setDigest(String digest) { this.digest = digest; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getSentAt() { return sentAt; }
}
