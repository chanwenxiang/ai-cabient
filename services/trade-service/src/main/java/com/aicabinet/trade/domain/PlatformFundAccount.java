package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 平台资金池账户台账（CB-033）。
 *
 * <p><b>设计要点</b>：每个出款渠道（{@code channel}，与 {@code payout_account.channel}
 * 同口径）<b>唯一一行</b>台账账户 —— {@code balance_cents} 是渠道账户余额的「系统内快照」，
 * 由 {@link PlatformFundFlow} 流水推进（充值/归集/调整人工登记，提现打款成功自动流出）。
 * 微信侧「余额不足付款失败」（官方口径）在本系统内的等价物就是这里的余额校验。</p>
 *
 * <p>🔴 余额<b>不追求与渠道实际余额逐分相等</b>（渠道侧还有分账冻结、退款回退等我们
 * 不可见的资金动作）；它只回答一个问题：<b>「今天要付的提现，池里的钱够不够」</b>。
 * 精确清分是渠道账单对账（{@code ReconciliationService}）的职责，两者口径刻意分离。</p>
 */
@TableName("platform_fund_account")
public class PlatformFundAccount {

    @TableId(type = IdType.AUTO)
    private Long accountId;

    private String channel;

    private String accountName;

    private long balanceCents;

    private String status = "ACTIVE";

    private String remark;

    private Instant createdAt;

    private Instant updatedAt;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public void setBalanceCents(long balanceCents) {
        this.balanceCents = balanceCents;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
