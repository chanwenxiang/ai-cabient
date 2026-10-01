package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("ad_campaign")
@Getter
@Setter
public class AdCampaign {

    @TableId(type = IdType.AUTO)
    private Long campaignId;
    private String name;
    private String status = "DRAFT";
    private String deviceScope = "ALL";
    /** 投放端：CABINET_SCREEN=柜机屏；MINI_PROGRAM=消费者小程序轮播位（V296，P3-6） */
    private String channel = "CABINET_SCREEN";
    /** 小程序轮播位点击跳转深链（可空） */
    private String linkUrl;
    private Instant startAt;
    private Instant endAt;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

}
