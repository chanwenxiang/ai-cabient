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
    private Instant startAt;
    private Instant endAt;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

}
