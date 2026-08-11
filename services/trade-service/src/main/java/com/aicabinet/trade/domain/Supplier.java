package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("supplier")
@Getter
@Setter
public class Supplier {
    @TableId(type = IdType.INPUT)
    private String supplierId;

    private String supplierName;
    private String contactName;
    private String contactPhone;
    private String status = "ACTIVE";
    private int paymentTermsDays = 30;
    private Long creditLimitCents;
    private Instant createdAt;

    public void setPaymentTermsDays(int paymentTermsDays) {
        this.paymentTermsDays = paymentTermsDays > 0 ? paymentTermsDays : 30;
    }
}
