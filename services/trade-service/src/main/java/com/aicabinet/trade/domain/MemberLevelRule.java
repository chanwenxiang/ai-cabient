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

    /**
     * D1 储值等级：升到该等级所需的**累计净充值**（元）。null = 该档无储值路径。
     *
     * <p>与 {@code minSpent}/{@code maxSpent} 是「或」关系：任一口径达标即升到该档，
     * 判定取最高达标档（见 {@code MemberService.calculateMemberLevel}）。
     */
    private java.math.BigDecimal minRecharge;

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
