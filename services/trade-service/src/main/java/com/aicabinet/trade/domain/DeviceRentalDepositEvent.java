package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 押金事件流水（CB-034）：DEDUCT 扣款抵扣 / RETURN 结算返还，金额恒为负（从余额流出）。 */
@TableName("device_rental_deposit_event")
@Getter
@Setter
public class DeviceRentalDepositEvent {

    @TableId(type = IdType.AUTO)
    private Long eventId;
    private Long depositId;
    private String eventType;
    private Long amountCents;
    private String reason;
    private Long operatorId;
    private Instant createdAt = Instant.now();
}
