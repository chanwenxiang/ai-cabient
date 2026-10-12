package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 平台资金池流水（CB-033）。
 *
 * <p>类型：{@code RECHARGE} 财务充值登记（+）、{@code SWEEP} 归集流出（−，
 * 渠道账户 → 公司对公账户）、{@code ADJUST} 人工调整（±，纠正渠道侧不可见动作）、
 * {@code PAYOUT} 商户提现打款流出（−，打款成功自动写入）。</p>
 */
@TableName("platform_fund_flow")
public class PlatformFundFlow {

    public static final String TYPE_RECHARGE = "RECHARGE";
    public static final String TYPE_SWEEP = "SWEEP";
    public static final String TYPE_ADJUST = "ADJUST";
    public static final String TYPE_PAYOUT = "PAYOUT";

    @TableId(type = IdType.AUTO)
    private Long flowId;

    private Long accountId;

    private String flowType;

    private long amountCents;

    private long balanceAfterCents;

    private String refType;

    private String refId;

    private Long operatorId;

    private String remark;

    private Instant createdAt;

    public Long getFlowId() {
        return flowId;
    }

    public void setFlowId(Long flowId) {
        this.flowId = flowId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getFlowType() {
        return flowType;
    }

    public void setFlowType(String flowType) {
        this.flowType = flowType;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    public long getBalanceAfterCents() {
        return balanceAfterCents;
    }

    public void setBalanceAfterCents(long balanceAfterCents) {
        this.balanceAfterCents = balanceAfterCents;
    }

    public String getRefType() {
        return refType;
    }

    public void setRefType(String refType) {
        this.refType = refType;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
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
}
