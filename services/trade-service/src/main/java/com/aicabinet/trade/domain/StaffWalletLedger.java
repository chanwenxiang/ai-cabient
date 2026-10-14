package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 补货员钱包流水（V359）。 */
@TableName("staff_wallet_ledger")
public class StaffWalletLedger {
    @TableId(type = IdType.AUTO)
    private Long ledgerId;
    private Long staffUserId;
    private String entryType;
    private Long amountCents;
    private Long balanceAfter;
    private Long frozenAfter;
    private String refType;
    private String refId;
    private String remark;
    private Instant createdAt;

    public Long getLedgerId() { return ledgerId; }
    public void setLedgerId(Long v) { this.ledgerId = v; }
    public Long getStaffUserId() { return staffUserId; }
    public void setStaffUserId(Long v) { this.staffUserId = v; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String v) { this.entryType = v; }
    public Long getAmountCents() { return amountCents; }
    public void setAmountCents(Long v) { this.amountCents = v; }
    public Long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Long v) { this.balanceAfter = v; }
    public Long getFrozenAfter() { return frozenAfter; }
    public void setFrozenAfter(Long v) { this.frozenAfter = v; }
    public String getRefType() { return refType; }
    public void setRefType(String v) { this.refType = v; }
    public String getRefId() { return refId; }
    public void setRefId(String v) { this.refId = v; }
    public String getRemark() { return remark; }
    public void setRemark(String v) { this.remark = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
