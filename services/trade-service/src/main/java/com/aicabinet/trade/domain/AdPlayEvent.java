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
    /** 展示位归属的活动（PromotionActivity）；柜机屏广告计划上报时为 null */
    private Long activityId;
    /** BANNER / POPUP_BEFORE_OPEN / POPUP_AFTER_CLOSE；设备侧上报不属于展示位，留 null */
    private String position;
    /** 小程序侧主体（登录消费者）；设备侧一机一密上报没有人的主体，留 null */
    private Long userId;
    private Long assetId;
    private String eventType;
    private Instant createdAt = Instant.now();
}
