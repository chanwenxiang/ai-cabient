package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "dispute_ticket")
public class DisputeTicket {

    @Id
    @Column(length = 32)
    private String ticketId;

    @Column(nullable = false, length = 32)
    private String sessionId;

    @Column(length = 256)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String items;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String resolutionItems;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public String getResolutionItems() { return resolutionItems; }
    public void setResolutionItems(String resolutionItems) { this.resolutionItems = resolutionItems; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
