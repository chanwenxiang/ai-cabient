package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("device_temp_plan_entry")
@Getter
@Setter
public class DeviceTempPlanEntry {

    @TableId(type = IdType.AUTO)
    private Long entryId;
    private Long planId;
    private int startMinute;
    private int targetTempC;

}
