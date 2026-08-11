package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("coupon_definition")
@Getter
@Setter
public class CouponDefinition {

    @TableId(type = IdType.AUTO)
    private Long couponDefId;

    private String couponName;

    private String couponType;

    private int denominationCents;

    private int minSpendCents;

    private Integer discountPercent;

    private int validityDays = 30;

    private int maxIssueCount;

    private int issuedCount;

    private Long activityId;

    private String deviceScope = "ALL";

    private String status = "ACTIVE";

    private String description;

    private Instant createdAt;

    private Instant updatedAt;

}
