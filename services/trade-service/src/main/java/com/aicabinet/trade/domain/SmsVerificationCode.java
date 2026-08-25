package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("sms_verification_code")
@Getter
@Setter
public class SmsVerificationCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phoneNumber;

    private String code;

    private Instant expiresAt;

    private Instant usedAt;

    private Instant createdAt;

}
