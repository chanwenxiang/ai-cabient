package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("finance_margin_daily_lock")
@Getter
@Setter
public class FinanceMarginDailyLock {
    @TableId(type = IdType.INPUT)
    private LocalDate bizDate;
    private long revenueCents;
    private long cogsCents;
    private long marginCents;
    private long writeOffCents;
    private long orderCount;
    private Instant lockedAt;
    private Long lockedBy;

}
