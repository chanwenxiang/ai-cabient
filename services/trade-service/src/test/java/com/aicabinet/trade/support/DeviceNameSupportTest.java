package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceNameSupportTest {

    @Test
    void resolve_corruptedName_returnsDemoDisplayName() {
        assertEquals(DeviceNameSupport.DEMO_DEVICE_NAME, DeviceNameSupport.resolve("any-id", "???-001"));
    }

    @Test
    void resolve_validName_unchanged() {
        assertEquals("门店一号柜", DeviceNameSupport.resolve("any-id", "门店一号柜"));
    }

    @Test
    void resolve_blank_fallsBackToDeviceId() {
        assertEquals("CAB-X", DeviceNameSupport.resolve("CAB-X", ""));
    }

    @Test
    void canonicalIfCorrupted_detectsQuestionMarks() {
        assertEquals(
                DeviceNameSupport.DEMO_DEVICE_NAME,
                DeviceNameSupport.canonicalIfCorrupted("any-id", "???-001")
        );
        assertNull(DeviceNameSupport.canonicalIfCorrupted("any-id", DeviceNameSupport.DEMO_DEVICE_NAME));
    }
}
