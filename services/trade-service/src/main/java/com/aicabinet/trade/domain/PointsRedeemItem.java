package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("points_redeem_item")
@Getter
@Setter
public class PointsRedeemItem {

    @TableId(type = IdType.AUTO)
    private Long itemId;

    private String title;

    private String subtitle;

    private String coverEmoji = "馃巵";

    private Integer pointsCost;

    private Long couponDefId;

    private Integer stockTotal = 0;

    private Integer redeemedCount = 0;

    private Integer sortOrder = 0;

    private String status = "ACTIVE";

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

}
