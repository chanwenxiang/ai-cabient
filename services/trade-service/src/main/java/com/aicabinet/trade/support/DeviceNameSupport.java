package com.aicabinet.trade.support;

import java.util.Map;

/** Normalizes device display names when DB rows were corrupted by encoding issues. */
public final class DeviceNameSupport {

    private static final Map<String, String> KNOWN_NAMES = Map.of(
            "CAB-001", "测试柜-001",
            "CAB-OTHER", "测试柜-OTHER"
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
