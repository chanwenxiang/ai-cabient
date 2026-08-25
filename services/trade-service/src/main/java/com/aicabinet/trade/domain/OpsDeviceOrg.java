package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_device_org")
@Getter
@Setter
public class OpsDeviceOrg {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long nodeId;
    private String deviceId;

}
