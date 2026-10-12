package com.aicabinet.trade.domain;

import com.aicabinet.common.constants.CabinetConstants;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 设备租赁月租应收账单（CB-034）：仅 coopMode=RENTAL 且 rentCents>0 出账；线下收款人工标记。 */
@TableName("device_rental_bill")
@Getter
@Setter
public class DeviceRentalBill {

    @TableId(type = IdType.AUTO)
    private Long billId;
    private String deviceId;
    private String deviceName;
    private String merchantId;
    /** 账期 YYYY-MM。 */
    private String billMonth;
    private Long amountCents;
    private String status = CabinetConstants.FEE_BILL_STATUS_UNPAID;
    private Instant paidAt;
    /** 欠租锁柜时间（解锁不清除，留痕）。 */
    private Instant lockAt;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
