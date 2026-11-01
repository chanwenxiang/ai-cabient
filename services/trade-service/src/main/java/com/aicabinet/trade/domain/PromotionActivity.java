package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "promotion_activity", autoResultMap = true)
@Getter
@Setter
public class PromotionActivity {

    @TableId(type = IdType.AUTO)
    private Long activityId;

    private String activityName;

    private String activityType;

    private String status = "DRAFT";

    private Instant startTime;

    private Instant endTime;

    private long budgetCents;

    private long usedCents;

    private int userLimit = 1;

    /** v1 活动定价参数（按 activity_type 取其一；CB-082） */
    private Integer reduceCents;
    private Integer discountBps;
    private Integer fixedPriceCents;

    private String deviceScope = "ALL";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String ruleConfig = "{}";

    private String description;

    private Long operatorId;

    private Instant createdAt;

    private Instant updatedAt;

}
