package com.aicabinet.trade.domain;

import com.aicabinet.common.constants.CabinetConstants;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("device_data_fee_bill")
@Getter
@Setter
public class DeviceDataFeeBill {

    @TableId(type = IdType.AUTO)
    private Long billId;
    private String deviceId;
    private String deviceName;
    private String merchantId;
    private String billMonth;
    private int amountCents;
    private String status = CabinetConstants.FEE_BILL_STATUS_UNPAID;
    private Instant paidAt;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
