package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("merchant_notify_log")
@Getter
@Setter
public class MerchantNotifyLog {

    @TableId(type = IdType.AUTO)
    private Long logId;

    private Long userId;

    private String digest;

    private String payload;

    private Instant sentAt;

}
