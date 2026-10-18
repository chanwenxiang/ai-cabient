package com.aicabinet.trade.domain;

import java.time.Instant;

/** 补货员钱包账户读模型（V359，对齐老系统 CommissionAccount）；V388 起数据在 {@code wallet_account}(domain=STAFF)。 */
public class StaffWalletAccount {
    private Long staffUserId;
    private Long balanceCents;
    private Instant updatedAt;

    public Long getStaffUserId() { return staffUserId; }
    public void setStaffUserId(Long v) { this.staffUserId = v; }
    public Long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(Long v) { this.balanceCents = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
