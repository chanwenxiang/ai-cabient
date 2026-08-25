package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_temp_plan")
@Getter
@Setter
public class DeviceTempPlan {

    @TableId(type = IdType.AUTO)
    private Long planId;
    private String deviceId;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

}
