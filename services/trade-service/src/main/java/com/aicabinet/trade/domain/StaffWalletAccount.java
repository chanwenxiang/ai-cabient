package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;

import java.time.Instant;

/** 补货员钱包账户（V359，对齐老系统 CommissionAccount）。 */
@TableName("staff_wallet_account")
public class StaffWalletAccount {
    @TableId(type = IdType.INPUT)
    private Long staffUserId;
    private Long balanceCents;
    private Long frozenCents;
    private Instant updatedAt;

    public Long getStaffUserId() { return staffUserId; }
    public void setStaffUserId(Long v) { this.staffUserId = v; }
    public Long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(Long v) { this.balanceCents = v; }
    public Long getFrozenCents() { return frozenCents; }
    public void setFrozenCents(Long v) { this.frozenCents = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
