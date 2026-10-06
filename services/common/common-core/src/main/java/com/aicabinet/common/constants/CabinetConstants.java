package com.aicabinet.common.constants;

public final class CabinetConstants {

    private CabinetConstants() {}
    // business status constants
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


    /**
     * 默认开门预授权冻结金额（分）= ¥20。
     * 与 {@code checkout.preauth_cents} / {@code CheckoutProperties} 默认值一致；
     * 柜机 {@code depositCents &gt; 0} 时可覆盖。
     */
    public static final int MIN_BALANCE_CENTS = 2000;

    /** 运营人员 userId 起始值（分配号段）；鉴权以 account_type 为准，号段仅作回退与分配 */
    public static final long OPERATOR_USER_ID_START = 100_000_000L;

    /** 账号类型：消费者 */
    public static final String ACCOUNT_TYPE_CONSUMER = "CONSUMER";
    /** 账号类型：运营（含商户门户运营号） */
    public static final String ACCOUNT_TYPE_OPERATOR = "OPERATOR";

    public static final String MQTT_EVENT_TYPE_DOOR = "DOOR";
    public static final String MQTT_EVENT_TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String MQTT_EVENT_TYPE_ACK = "ACK";
    /** O4（2026-09-19）：edge 侧 `publishAlert` 与 `EDGE_QUEUE_ABANDON` 都在发 `type=ALERT`，
     *  device-service `MqttEventListener` 也真的在分发它；此前只在监听器里**局部**定义过，
     *  与 CDC 契约（proto/edge）对不上账 ⇒ 收敛到这里。 */
    public static final String MQTT_EVENT_TYPE_ALERT = "ALERT";
    /**
     * V308：设备自检结构化结果（edge `publishSelfTestReport` 发）。
     *
     * <p>与 {@link #MQTT_EVENT_TYPE_ALERT} 的区别：ALERT 是<b>单次告警文本</b>，
     * 本类型是<b>可聚合的健康指标快照</b>（可用存储 / 运行时长 / 门状态 / RSSI），
     * 后台要按时间序列看趋势并设阈值 —— 混在 ALERT 文本里只能靠正则解析。
     */
    public static final String MQTT_EVENT_TYPE_SELF_TEST = "SELF_TEST_REPORT";

    public static final String MQTT_CMD_OPEN_DOOR = "OPEN_DOOR";
    public static final String MQTT_CMD_SET_TARGET_TEMP = "SET_TARGET_TEMP";
    public static final String MQTT_CMD_LOCK = "LOCK";
    public static final String MQTT_CMD_UNLOCK = "UNLOCK";
    public static final String MQTT_CMD_REBOOT = "REBOOT";
    /**
     * V308：远程触发设备自检。
     *
     * <p>🔴 此前 edge 端只认 {@link #MQTT_CMD_OPEN_DOOR}，本命令与上面 4 个运维指令
     * 全部被静默丢弃、不回 ACK ⇒ 云端等满 15s {@code ACK_TIMEOUT_MS} 才置 TIMEOUT。
     * V308 起这5 条在 edge 端都有明确分支（未装配执行器时回<b>失败</b> ACK，
     * 而非静默丢弃 —— 静默丢弃会让运维误以为「下发成功、设备没动」）。
     */
    public static final String MQTT_CMD_SELF_TEST = "SELF_TEST";

    /** 万分比满额（100% = 10000 bps） */
    public static final int SHARE_BPS_FULL = 10_000;

    /** 运营周期费用账单状态 */
    public static final String FEE_BILL_STATUS_UNPAID = "UNPAID";
    public static final String FEE_BILL_STATUS_PAID = "PAID";
    public static final String FEE_BILL_STATUS_VOID = "VOID";

    /** 场地租金分账角色 */
    public static final String RENT_PARTY_LANDLORD = "LANDLORD";
    public static final String RENT_PARTY_PLATFORM = "PLATFORM";
    public static final String RENT_PARTY_MERCHANT = "MERCHANT";
    public static final String RENT_PARTY_FRANCHISE = "FRANCHISE";
    public static final String RENT_PARTY_OTHER = "OTHER";

    /** 周期费用类型 */
    public static final String FEE_TYPE_SITE_RENT = "SITE_RENT";
    public static final String FEE_TYPE_DATA_FEE = "DATA_FEE";
}

