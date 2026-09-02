package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("site_rent_bill")
@Getter
@Setter
public class SiteRentBill {

    @TableId(type = IdType.AUTO)
    private Long billId;
    private Long contractId;
    private String deviceId;
    private String siteName;
    private String billMonth;
    private String partyType;
    private String partyId;
    private int shareBps;
    private int fixedCents;
    private int baseFeeCents;
    private int amountCents;
    private String status = "UNPAID";
    private Instant paidAt;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
