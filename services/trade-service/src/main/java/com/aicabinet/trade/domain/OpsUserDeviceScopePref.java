package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_user_device_scope_pref")
@Getter
@Setter
public class OpsUserDeviceScopePref {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private String scopeMode;
    private Instant updatedAt;

}
