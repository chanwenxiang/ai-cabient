package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("member_points_log")
@Getter
@Setter
public class MemberPointsLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private Integer points;

    private String pointsType;

    private String sourceType;

    private String sourceId;

    private String description;

    private Instant createdAt = Instant.now();

    private Instant expireAt;

    private Instant expiredAt;

    private Instant remindedAt;

}
