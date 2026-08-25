package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("ad_campaign_device")
@Getter
@Setter
public class AdCampaignDevice {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campaignId;
    private String deviceId;

}
