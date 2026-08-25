package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("member")
@Getter
@Setter
public class Member {
    @TableId(type = IdType.AUTO)
    private Long memberId;

    private Long userId;

    private String memberLevel;

    private Integer totalPoints = 0;

    private Integer availablePoints = 0;

    private Integer usedPoints = 0;

    private Integer expiredPoints = 0;

    private java.math.BigDecimal totalSpent;

    private Integer orderCount = 0;

    private String inviteCode;

    private Long invitedBy;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    private Instant levelUpgradeAt;














}
