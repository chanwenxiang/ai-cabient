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

    /** R20-5：本行已过期掉的积分数（部分结转累计值，见 V459）。 */
    private Integer expiredPoints;

    /**
     * R20-10：EARN 行**发放时**的积分倍率（1 元 = N 积分，见 V462）。
     *
     * <p>退款扣回按它算，而不是按退款当时的等级倍率——否则「下单后升级再退款」会多扣
     * 用户从没拿到的那部分。历史行为 null（无法反推），此时回落当前等级倍率。
     */
    private java.math.BigDecimal earnRate;

    private Instant expiredAt;

    private Instant remindedAt;

}
