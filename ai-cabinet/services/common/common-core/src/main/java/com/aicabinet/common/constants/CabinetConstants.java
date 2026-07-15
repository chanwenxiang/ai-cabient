package com.aicabinet.common.constants;

public final class CabinetConstants {

    private CabinetConstants() {}

    /** 最低余额（分），参考旧 M8Constants.ACCOUNT_MIN_BALACE = 500（5元） */
    public static final int MIN_BALANCE_CENTS = 500;

    /** 运营人员 userId 起始值，运营账号跳过实名/余额校验 */
    public static final long OPERATOR_USER_ID_START = 100_000_000L;

    public static final String MQTT_EVENT_TYPE_DOOR = "DOOR";
    public static final String MQTT_EVENT_TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String MQTT_EVENT_TYPE_ACK = "ACK";

    public static final String MQTT_CMD_OPEN_DOOR = "OPEN_DOOR";
}
