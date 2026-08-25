package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("ad_play_event")
@Getter
@Setter
public class AdPlayEvent {
    @TableId(type = IdType.AUTO)
    private Long eventId;
    private Long campaignId;
    private String deviceId;
    private Long assetId;
    private String eventType;
    private Instant createdAt = Instant.now();
}
