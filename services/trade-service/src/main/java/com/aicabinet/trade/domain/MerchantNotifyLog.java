package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("merchant_notify_log")
public class MerchantNotifyLog {

    @TableId(type = IdType.AUTO)
    private Long logId;

    private Long userId;

    private String digest;

    private String payload;

    private Instant sentAt;

public Long getLogId() { return logId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDigest() { return digest; }
    public void setDigest(String digest) { this.digest = digest; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getSentAt() { return sentAt; }
}
