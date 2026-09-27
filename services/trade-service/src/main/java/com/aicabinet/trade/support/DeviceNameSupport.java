package com.aicabinet.trade.support;

/**
 * Normalizes device display names when DB rows were corrupted by encoding issues.
 *
 * <p>柜机编号由系统随机分配（见 {@code DeviceIdService}），此处<strong>不</strong>再按固定
 * deviceId 映射显示名。演示默认名仅作损坏名修复时的兜底文案。
 */
public final class DeviceNameSupport {

    public static final String DEMO_DEVICE_NAME = "演示智能柜";
    public static final String DEMO_DEVICE_NAME_OTHER = "门店二号柜";

    private DeviceNameSupport() {
    }

    public static boolean isCorrupted(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.contains("???") || name.chars().filter(ch -> ch == '?').count() >= 2;
    }

    public static String resolve(String deviceId, String storedName) {
        if (storedName != null && !storedName.isBlank() && !isCorrupted(storedName)) {
            return storedName;
        }
        if (isCorrupted(storedName)) {
            return DEMO_DEVICE_NAME;
        }
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        return deviceId != null ? deviceId : "";
    }

    /**
     * Returns {@link #DEMO_DEVICE_NAME} when stored value is corrupted; otherwise null
     * （调用方据此判断「无需写库」）。
     */
    public static String canonicalIfCorrupted(String deviceId, String storedName) {
        if (!isCorrupted(storedName)) {
            return null;
        }
        return DEMO_DEVICE_NAME;
    }
}
