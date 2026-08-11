package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("ad_campaign_item")
@Getter
@Setter
public class AdCampaignItem {

    @TableId(type = IdType.AUTO)
    private Long itemId;
    private Long campaignId;
    private Long assetId;
    private int sortOrder;

}
