package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("user_coupon")
@Getter
@Setter
public class UserCoupon {

    @TableId(type = IdType.AUTO)
    private Long couponId;

    private Long userId;

    private Long couponDefId;

    private String couponCode;

    private String status = "UNUSED";

    private Instant receivedAt;

    private Instant usedAt;

    private Instant expireAt;

    private Instant remindedAt;

    private String orderId;

    private String deviceId;

    private Integer discountCents;

    private Instant createdAt;

}
