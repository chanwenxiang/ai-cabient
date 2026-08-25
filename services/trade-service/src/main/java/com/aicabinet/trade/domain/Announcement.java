package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("announcement")
@Getter
@Setter
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long announceId;

    private String title;

    private String content;

    private String announceType = "SYSTEM";

    private String targetScope = "ALL";

    private String targetDevice;

    private String priority = "NORMAL";

    private String status = "DRAFT";

    private Instant publishAt;
    private Instant expireAt;
    private Long operatorId;

    private Instant createdAt;

    private Instant updatedAt;

}
