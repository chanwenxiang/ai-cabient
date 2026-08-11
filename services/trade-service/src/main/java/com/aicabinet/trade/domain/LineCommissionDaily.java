package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Daily commission posting row for a line manager + device. */
@TableName("line_commission_daily")
@Getter
@Setter
public class LineCommissionDaily {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long managerId;
    private LocalDate bizDate;
    private String deviceId;
    private Integer orderCount;
    private Long gmvCents;
    private Long commissionCents;
    private String status;
    private Instant createdAt;


















}
