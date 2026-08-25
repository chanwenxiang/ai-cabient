package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("phone_verify_log")
@Getter
@Setter
public class PhoneVerifyLog {
    @TableId(type = IdType.AUTO)
    private Long logId;
    private Long userId;
    private String phone;
    private String channel;
    private String merchantId;
    private Instant verifiedAt;

}
