package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("member_level_rule")
@Getter
@Setter
public class MemberLevelRule {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String levelCode;

    private String levelName;

    private java.math.BigDecimal minSpent;

    private java.math.BigDecimal maxSpent;

    private Integer minPoints = 0;

    private Integer maxPoints;

    private java.math.BigDecimal pointsRate = java.math.BigDecimal.ONE;

    /** 会员价折扣百分比：5 表示 95 折，0 表示无会员价 */
    private java.math.BigDecimal priceDiscountPct = java.math.BigDecimal.ZERO;

    private Integer sortorder;

    private String status;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;












}
