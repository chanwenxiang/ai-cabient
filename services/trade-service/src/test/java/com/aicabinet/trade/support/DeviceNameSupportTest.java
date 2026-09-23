package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNameSupportTest {

    @Test
    void resolve_corruptedDemoDevice_returnsCanonicalName() {
        assertEquals(DeviceNameSupport.DEMO_DEVICE_NAME, DeviceNameSupport.resolve("CAB-001", "???-001"));
    }

    @Test
    void resolve_validName_unchanged() {
        assertEquals("门店一号柜", DeviceNameSupport.resolve("CAB-999", "门店一号柜"));
    }

    @Test
    void resolve_blank_fallsBackToDeviceId() {
        assertEquals("CAB-X", DeviceNameSupport.resolve("CAB-X", ""));
    }

    @Test
    void canonicalIfCorrupted_detectsQuestionMarks() {
        assertEquals(
                DeviceNameSupport.DEMO_DEVICE_NAME,
                DeviceNameSupport.canonicalIfCorrupted("CAB-001", "???-001")
        );
        // 已经是规范名 ⇒ 不需要修复（返回 null，调用方据此判断「无需写库」）
        assertNull(DeviceNameSupport.canonicalIfCorrupted("CAB-001", DeviceNameSupport.DEMO_DEVICE_NAME));
    }
}
