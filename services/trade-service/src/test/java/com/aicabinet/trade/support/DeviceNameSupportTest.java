package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNameSupportTest {

    @Test
    void resolve_corruptedDemoDevice_returnsCanonicalName() {
        assertEquals("测试柜-001", DeviceNameSupport.resolve("CAB-001", "???-001"));
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
        assertEquals("测试柜-001", DeviceNameSupport.canonicalIfCorrupted("CAB-001", "???-001"));
        assertNull(DeviceNameSupport.canonicalIfCorrupted("CAB-001", "测试柜-001"));
    }
}
