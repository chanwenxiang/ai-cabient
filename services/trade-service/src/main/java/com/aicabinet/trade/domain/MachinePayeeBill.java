package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 机器账单（V356，付场地方，线下结算）：PENDING → 线下收款 → 运营标记 SETTLED。 */
@TableName("machine_payee_bill")
public class MachinePayeeBill {
    @TableId(type = IdType.AUTO)
    private Long billId;
    private String deviceId;
    private String billMonth;
    private String feeType;
    private Long amountCents;
    private Long gmvCents;
    private String payeeName;
    private String payeePhone;
    private String status;
    private Instant settledAt;
    private String remark;
    private Instant createdAt;

    public Long getBillId() { return billId; }
    public void setBillId(Long v) { this.billId = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public String getBillMonth() { return billMonth; }
    public void setBillMonth(String v) { this.billMonth = v; }
    public String getFeeType() { return feeType; }
    public void setFeeType(String v) { this.feeType = v; }
    public Long getAmountCents() { return amountCents; }
    public void setAmountCents(Long v) { this.amountCents = v; }
    public Long getGmvCents() { return gmvCents; }
    public void setGmvCents(Long v) { this.gmvCents = v; }
    public String getPayeeName() { return payeeName; }
    public void setPayeeName(String v) { this.payeeName = v; }
    public String getPayeePhone() { return payeePhone; }
    public void setPayeePhone(String v) { this.payeePhone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public String getRemark() { return remark; }
    public void setRemark(String v) { this.remark = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
