package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("user_blacklist")
@Getter
@Setter
public class UserBlacklist {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private String reason;

    private String source = "MANUAL";

    private Instant expiresAt;

    private Instant createdAt;

}
