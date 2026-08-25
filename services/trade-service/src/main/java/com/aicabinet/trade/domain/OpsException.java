package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_exception")
@Getter
@Setter
public class OpsException {
    @TableId(type = IdType.INPUT)
    private String exceptionId;
    private String exceptionType;
    private String severity;
    private String status;
    private String deviceId;
    private String sessionId;
    private String orderId;
    private Long userId;
    private String title;
    private String detail;
    private Long assigneeUserId;
    private String resolution;
    private String dedupKey;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private Instant slaDueAt;
    private Boolean archived;
    private Instant archivedAt;

    public void setExceptionId(String v) { exceptionId = v; }
    public void setExceptionType(String v) { exceptionType = v; }
    public void setSeverity(String v) { severity = v; }
    public void setStatus(String v) { status = v; }
    public void setDeviceId(String v) { deviceId = v; }
    public void setSessionId(String v) { sessionId = v; }
    public void setOrderId(String v) { orderId = v; }
    public void setUserId(Long v) { userId = v; }
    public void setTitle(String v) { title = v; }
    public void setDetail(String v) { detail = v; }
    public void setAssigneeUserId(Long v) { assigneeUserId = v; }
    public void setResolution(String v) { resolution = v; }
    public void setDedupKey(String v) { dedupKey = v; }
    public void setResolvedAt(Instant v) { resolvedAt = v; }
    public void setSlaDueAt(Instant v) { slaDueAt = v; }
    public void setArchived(Boolean v) { archived = v; }
    public void setArchivedAt(Instant v) { archivedAt = v; }
}
