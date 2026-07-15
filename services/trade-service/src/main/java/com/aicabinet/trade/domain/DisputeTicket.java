package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;

@TableName(value = "dispute_ticket", autoResultMap = true)
public class DisputeTicket {

    @TableId(type = IdType.INPUT)
    private String ticketId;

    private String sessionId;

    private String reason;

    private String status;

    private String category = "RECOGNITION";

    private String priority = "NORMAL";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String items;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String resolutionItems;

    private Instant createdAt;

    private Instant resolvedAt;

    private Instant slaDueAt;

    private Instant slaReminderAt;

    private Instant slaAlertedAt;

    private String operatorNote;

    private Instant closedAt;

    private Instant reopenedAt;

public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public String getResolutionItems() { return resolutionItems; }
    public void setResolutionItems(String resolutionItems) { this.resolutionItems = resolutionItems; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(Instant slaDueAt) { this.slaDueAt = slaDueAt; }
    public Instant getSlaReminderAt() { return slaReminderAt; }
    public void setSlaReminderAt(Instant slaReminderAt) { this.slaReminderAt = slaReminderAt; }
    public Instant getSlaAlertedAt() { return slaAlertedAt; }
    public void setSlaAlertedAt(Instant slaAlertedAt) { this.slaAlertedAt = slaAlertedAt; }
    public String getOperatorNote() { return operatorNote; }
    public void setOperatorNote(String operatorNote) { this.operatorNote = operatorNote; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Instant getReopenedAt() { return reopenedAt; }
    public void setReopenedAt(Instant reopenedAt) { this.reopenedAt = reopenedAt; }
}
