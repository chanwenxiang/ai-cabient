package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * OTA 设备上报行（表 {@code ota_device_report}）：一台设备一行。
 *
 * <p>该表自 V9 建表起长期无人使用（零 java 引用），2026-09-20 O2 补齐进度上报后
 * 成为真正的上报表 —— 把「当前版本 + 升级过程 + 升级终态」收敛在同一行。
 */
@TableName("ota_device_report")
@Getter
@Setter
public class OtaDeviceReport {

    @TableId(type = IdType.INPUT)
    private String deviceId;

    /** 设备当前**实际**运行的版本；SUCCESS 上报时由服务端改写为目标版本。 */
    private String appVersion;

    /** 本次升级的目标版本；无升级中任务时为 null。 */
    private String targetVersion;

    /** IDLE / DOWNLOADING / INSTALLING / SUCCESS / FAILED。 */
    private String upgradeStatus = "IDLE";

    private int progressPercent;

    /** 失败原因；仅 FAILED 有值。 */
    private String errorMessage;

    private Instant reportedAt;

    private Instant updatedAt;
}
