package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("line_wallet_account")
public class LineWalletAccount {

    @TableId
    private Long managerId;
    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;

    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public Long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(Long balanceCents) { this.balanceCents = balanceCents; }
    public Long getFrozenCents() { return frozenCents; }
    public void setFrozenCents(Long frozenCents) { this.frozenCents = frozenCents; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
