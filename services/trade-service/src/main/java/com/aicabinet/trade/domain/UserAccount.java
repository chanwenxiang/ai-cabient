package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("user_account")
public class UserAccount {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private int balanceCents;

    private int frozenCents;

    private Instant updatedAt;

public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getBalanceCents() { return balanceCents; }
    public void setBalanceCents(int balanceCents) { this.balanceCents = balanceCents; }
    public int getFrozenCents() { return frozenCents; }
    public void setFrozenCents(int frozenCents) { this.frozenCents = frozenCents; }
    public Instant getUpdatedAt() { return updatedAt; }
}
