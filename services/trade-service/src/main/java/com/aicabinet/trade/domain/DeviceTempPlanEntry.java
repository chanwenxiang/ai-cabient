package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("device_temp_plan_entry")
public class DeviceTempPlanEntry {

    @TableId(type = IdType.AUTO)
    private Long entryId;
    private Long planId;
    private int startMinute;
    private int targetTempC;

    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public int getStartMinute() { return startMinute; }
    public void setStartMinute(int startMinute) { this.startMinute = startMinute; }
    public int getTargetTempC() { return targetTempC; }
    public void setTargetTempC(int targetTempC) { this.targetTempC = targetTempC; }
}
