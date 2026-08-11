package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("device_availability_kpi_daily")
@Getter
@Setter
public class DeviceAvailabilityKpiDaily {

    @TableId(type = IdType.INPUT)
    private LocalDate kpiDate;

    private Integer deviceTotal;
    private Integer offlineEvents;
    private Integer autoLockCount;
    private Integer autoUnlockCount;
    private Integer manualUnlockCount;
    private Double avgLockHours;
    private Double avgRecoverHours;
    private Double manualInterventionRate;
    private Instant createdAt;

}
