package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import com.aicabinet.common.enums.SessionState;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "shopping_session", autoResultMap = true)
@Getter
@Setter
public class ShoppingSession {

    @TableId(type = IdType.INPUT)
    private String sessionId;

    private Long userId;

    private String deviceId;

    /** 设备名称冗余（开门写入） */
    private String deviceName;

    private SessionState state;

    private Instant openTime;
    private Instant closeTime;

    private String orderId;

    private String failReason;

    private String recognitionTaskId;

    private String videoUri;

    private String uploadStatus = "NONE";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String videoClips;

    private String cameraFusionMode = "SINGLE";

    private String idempotencyKey;

    private Long replenishmentTaskId;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String gravityDeltas;

    /**
     * 开门中实时购物车 JSON（第三方识别推送；仅 C 端展示，结算以最终识别/重力为准）。
     */
    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String liveCart;

    /** 扫码入口渠道 WECHAT / ALIPAY */
    private String entryChannel;

    /** 开门预授权冻结金额（分） */
    private int preauthCents;

    /** NONE / FROZEN / CAPTURED / RELEASED */
    private String preauthStatus = "NONE";

    /** 开门前用户指定优惠券 ID（结算优先；无效回退自动择优） */
    private Long preferredCouponId;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
