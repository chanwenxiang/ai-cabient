package com.aicabinet.trade.support;

import java.util.Map;

/**
 * Normalizes device display names when DB rows were corrupted by encoding issues.
 *
 * <p>本表是这些柜机显示名的**唯一运行时权威**：种子（{@code DemoDataService}）与迁移
 * （{@code V286__rename_demo_fixtures.sql}）都对齐这里，改名字只改这一处。
 *
 * <p>2026-09-23：名称由「测试柜-001 / 测试柜-OTHER」改为拟真名 —— 旧名会随 V2/V57
 * 落到任何环境（含生产），上线时还得再改一次（见 V286 头注释）。
 */
public final class DeviceNameSupport {

    public static final String DEMO_DEVICE_NAME = "门店一号柜";
    public static final String DEMO_DEVICE_NAME_OTHER = "门店二号柜";

    private static final Map<String, String> KNOWN_NAMES = Map.of(
            "CAB-001", DEMO_DEVICE_NAME,
            "CAB-OTHER", DEMO_DEVICE_NAME_OTHER
    );

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
        String known = KNOWN_NAMES.get(deviceId);
        if (known != null) {
            return known;
        }
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        return deviceId != null ? deviceId : "";
    }

    /** Returns canonical demo name when stored value is corrupted; otherwise null. */
    public static String canonicalIfCorrupted(String deviceId, String storedName) {
        if (!isCorrupted(storedName)) {
            return null;
        }
        return KNOWN_NAMES.get(deviceId);
    }
}
