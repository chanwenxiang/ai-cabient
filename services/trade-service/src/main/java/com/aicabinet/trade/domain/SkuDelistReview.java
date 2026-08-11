package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("sku_delist_review")
@Getter
@Setter
public class SkuDelistReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuId;

    private String reviewStatus = "PENDING";

    private String performanceLevel;

    private Integer salesQty = 0;

    private Long revenueCents = 0L;

    private Integer stockDays;

    private String actionType;

    private String reason;

    private String replaceSkuId;

    private Long reviewedBy;

    private Instant reviewedAt;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

}
