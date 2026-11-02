package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 活动展示位（V419/V420，CB-082）：每活动 banner 必选，两个弹窗位最多一个。 */
@TableName("promotion_placement")
@Getter
@Setter
public class PromotionPlacement {

    @TableId(type = IdType.AUTO)
    private Long placementId;

    private Long activityId;

    private String position;

    private Long assetId;

    private Integer imageWidth;

    private Integer imageHeight;

    /** 轮播顺序：数值小在前；相同值按 activity_id/placement_id（V434）。 */
    private Integer sortOrder;

    private Instant createdAt;
}
