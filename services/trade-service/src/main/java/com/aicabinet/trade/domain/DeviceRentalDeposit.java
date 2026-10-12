package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 设备租赁押金台账（CB-034）：每台 RENTAL 柜至多一条；押金是负债不是收入。 */
@TableName("device_rental_deposit")
@Getter
@Setter
public class DeviceRentalDeposit {

    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String EVENT_DEDUCT = "DEDUCT";
    public static final String EVENT_RETURN = "RETURN";

    @TableId(type = IdType.AUTO)
    private Long depositId;
    private String deviceId;
    private String merchantId;
    /** 押金本金（分）。 */
    private Long amountCents;
    /** 剩余可退押金（分）= 本金 - Σ扣款。 */
    private Long balanceCents;
    private String status = STATUS_HELD;
    private Instant settledAt;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
