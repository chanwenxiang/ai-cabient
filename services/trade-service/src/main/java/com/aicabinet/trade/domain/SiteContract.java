package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("site_contract")
@Getter
@Setter
public class SiteContract {

    @TableId(type = IdType.AUTO)
    private Long contractId;
    private String deviceId;
    private String siteName;
    private String address;
    private String landlordName;
    private String landlordPhone;
    private LocalDate startDate;
    private LocalDate endDate;
    private int monthlyFeeCents;
    private String status = "ACTIVE";
    private String remark;
    private Instant createdAt;
    private Instant updatedAt;

}
