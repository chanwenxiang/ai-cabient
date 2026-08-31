package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_info")
@Getter
@Setter
public class DeviceInfo {

    @TableId(type = IdType.INPUT)
    private String deviceId;

    /** 自增数字 ID，列表展示用；业务主键仍为 deviceId */
    private Long id;

    private String deviceName;

    private String deviceType;

    private String onlineStatus;

    /** 最近一次恢复在线的时间（离线时置空），用于稳定在线自动解锁 */
    private Instant onlineSince;

    private String appVersion;

    private String firmwareVersion;

    private Double latitude;
    private Double longitude;

    private String address;

    private String merchantId;

    private String alertContactName;

    private String alertContactPhone;

    private Integer targetTempC;

    private Integer currentTempC;

    private Instant tempReportedAt;

    private String opsRemark;

    /** AUTO_REFUND | DISPUTE_ONLY | null=继承全局 */
    private String refundPolicy;

    /** 营业锁机：禁止消费者开门 */
    private Boolean salesLocked;

    /** 停售原因（人工锁机 / 离线超时等） */
    private String salesLockReason;

    /** 最近一次解锁时间（离线自动锁机宽限用） */
    private Instant salesUnlockedAt;

    /** 价格锁：禁止改价 */
    private Boolean priceLocked;

    /** 禁止改 SKU / 货道商品 */
    private Boolean skuEditForbidden;

    /** 禁售（策略层，通常伴随营业锁机） */
    private Boolean saleForbidden;

    /** INBOUND|IDLE|DEPLOYED|RETURNING|RETIRED */
    private String lifecycleStatus;

    private String imei;

    private String assetOwner;

    /** SELF|FRANCHISE|CONSIGN */
    private String coopMode;

    private Long depositCents;

    private Long dataFeeCents;

    private String opsTags;

    private String routeCode;

    private Instant deployedAt;

    private String lifecycleRemark;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic(value = "false", delval = "true")
    @TableField("is_deleted")
    private Boolean deleted;

    public boolean salesLockedEnabled() { return Boolean.TRUE.equals(salesLocked); }
    public void markHeartbeatReceived() { updatedAt = Instant.now(); }
}
