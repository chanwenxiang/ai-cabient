package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;

import java.time.Instant;

/** 柜机点位费用/分成配置（V356，付场地方；对齐老系统 MachinePositionBaseInfo 模型）。 */
@TableName("machine_position_config")
public class MachinePositionConfig {
    @TableId(type = IdType.INPUT)
    private String deviceId;
    private String electricType;
    private Long electricAmountCents;
    private String welfareInfo;
    private Integer shareBps;
    private String payeeName;
    private String payeePhone;
    private Integer enabled;
    private Instant updatedAt;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public String getElectricType() { return electricType; }
    public void setElectricType(String v) { this.electricType = v; }
    public Long getElectricAmountCents() { return electricAmountCents; }
    public void setElectricAmountCents(Long v) { this.electricAmountCents = v; }
    public String getWelfareInfo() { return welfareInfo; }
    public void setWelfareInfo(String v) { this.welfareInfo = v; }
    public Integer getShareBps() { return shareBps; }
    public void setShareBps(Integer v) { this.shareBps = v; }
    public String getPayeeName() { return payeeName; }
    public void setPayeeName(String v) { this.payeeName = v; }
    public String getPayeePhone() { return payeePhone; }
    public void setPayeePhone(String v) { this.payeePhone = v; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer v) { this.enabled = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
