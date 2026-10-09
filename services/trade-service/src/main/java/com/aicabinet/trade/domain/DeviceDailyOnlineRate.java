package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 柜机×日在线率（CB-018，V329）。
 * <p>联合主键 (kpi_date, device_id)：MyBatis-Plus 不支持联合主键注解，
 * 故不设 {@code @TableId}，所有读写经 {@code DeviceDailyOnlineRateMapper} 的
 * wrapper 条件（findByDateAndDevice / update(entity, wrapper)）完成，
 * 严禁调用依赖单主键的 selectById/updateById。</p>
 */
@TableName("device_daily_online_rate")
@Getter
@Setter
public class DeviceDailyOnlineRate {

    private LocalDate kpiDate;
    private String deviceId;
    private Integer offlineMinutes;
    private Integer onlineMinutes;
    private Double rate;
    private Instant computedAt;
}
