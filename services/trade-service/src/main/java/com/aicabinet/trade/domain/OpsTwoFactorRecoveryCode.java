package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("ops_2fa_recovery_code")
public class OpsTwoFactorRecoveryCode {

    private Long userId;
    private String codeHash;
    private boolean used;
    private Instant createdAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
