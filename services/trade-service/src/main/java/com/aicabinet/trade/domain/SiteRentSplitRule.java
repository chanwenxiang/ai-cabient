package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@TableName("site_rent_split_rule")
@Getter
@Setter
public class SiteRentSplitRule {
    @TableId(type = IdType.AUTO)
    private Long ruleId;
    private Long contractId;
    private String partyType;
    private String partyId;
    private int shareBps;
    private int fixedCents;
    private String status = "ACTIVE";
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
