package com.aicabinet.common.constants;

public final class CabinetConstants {

    private CabinetConstants() {}
    // ── 业务状态常量 ──
    public static final String ORDER_STATUS_PAID = "PAID";
    public static final String ORDER_STATUS_REFUNDED = "REFUNDED";
    public static final String ORDER_STATUS_DISPUTED = "DISPUTED";
    public static final String ORDER_STATUS_FAILED = "FAILED";

    public static final String SESSION_TIMEOUT_REASON = "开门超时";
    public static final String OPS_CANCEL_REASON = "运营终止会话";

    public static final String DEVICE_ONLINE = "ONLINE";
    public static final String DEVICE_OFFLINE = "OFFLINE";

    public static final String UPLOAD_STATUS_UPLOADED = "UPLOADED";
    public static final String UPLOAD_STATUS_LOCAL_QUEUED = "LOCAL_QUEUED";
    public static final String UPLOAD_STATUS_UPLOADING = "UPLOADING";
    public static final String UPLOAD_STATUS_FAILED = "UPLOAD_FAILED";

    public static final String COUPON_STATUS_UNUSED = "UNUSED";
    public static final String COUPON_STATUS_USED = "USED";
    public static final String COUPON_STATUS_EXPIRED = "EXPIRED";

    public static final String PROMOTION_STATUS_DRAFT = "DRAFT";
    public static final String PROMOTION_STATUS_ACTIVE = "ACTIVE";
    public static final String PROMOTION_STATUS_STOPPED = "STOPPED";

    public static final String SKU_STATUS_ACTIVE = "ACTIVE";
    public static final String SKU_STATUS_DISABLED = "DISABLED";

    public static final String VISION_MODE_DELTA = "delta";
    public static final String VISION_MODE_SINGLE_FRAME = "single_frame";

    public static final String PAY_CHANNEL_BALANCE = "BALANCE";
    public static final String PAY_CHANNEL_WECHAT = "WECHAT";
    public static final String PAY_CHANNEL_ALIPAY = "ALIPAY";


    /** 最低余额（分），参考旧 M8Constants.ACCOUNT_MIN_BALACE = 500（5元） */
    public static final int MIN_BALANCE_CENTS = 500;

    /** 运营人员 userId 起始值，运营账号跳过实名/余额校验 */
    public static final long OPERATOR_USER_ID_START = 100_000_000L;

    public static final String MQTT_EVENT_TYPE_DOOR = "DOOR";
    public static final String MQTT_EVENT_TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String MQTT_EVENT_TYPE_ACK = "ACK";

    public static final String MQTT_CMD_OPEN_DOOR = "OPEN_DOOR";
    public static final String MQTT_CMD_SET_TARGET_TEMP = "SET_TARGET_TEMP";
}

