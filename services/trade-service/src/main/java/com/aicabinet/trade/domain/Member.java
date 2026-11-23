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

    /** V501：被邀请绑定时间（NULL=未绑定邀请人）。 */
    private Instant invitedAt;

    /** V501：绑定即发的新人首单券（{@code user_coupon.coupon_id}；无券模板时为 NULL）。 */
    private Long inviteeCouponId;

    /**
     * V501：该会员首单支付后、把奖励发给邀请人的时间（NULL=未结算）。
     * 支付成功的三条路径都会走 {@code onOrderPaid}，靠这一列做结算幂等。
     */
    private Instant inviteSettledAt;

    /** V501：发给邀请人的奖励券（{@code user_coupon.coupon_id}）。 */
    private Long inviterRewardCouponId;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    private Instant levelUpgradeAt;














}
