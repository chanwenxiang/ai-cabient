package com.aicabinet.common.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectStorageKeysTest {

    private static final Instant AT = Instant.parse("2026-07-13T04:00:00Z");

    @Test
    void shoppingVideoKeyAt_usesShanghaiDateAndUserFolder() {
        String key = ObjectStorageKeys.shoppingVideoKeyAt(
                "CAB-001", 10086L, "sess-abc123", "top", ".mp4", AT);
        assertEquals("videos/2026/07/13/CAB-001/user-10086/sess-abc123-top.mp4", key);
    }

    @Test
    void simMediaKey_normalizesCameraAndExtension() {
        String key = ObjectStorageKeys.simMediaKey("sim-device", 0L, "S-TEST", "SIDE", "jpg");
        assert key.startsWith("sim/");
        assert key.contains("/sim-device/user-0/S-TEST-side.jpg");
    }

    @Test
    void archiveVideoKey_usesSkuAndUserFolder() {
        String key = ObjectStorageKeys.archiveVideoKey(
                "SKU-WATER-001", 10086L, "sess-abc", "top", ".mp4", AT);
        assertEquals("archive/2026/07/13/SKU-WATER-001/user-10086/sess-abc-top.mp4", key);
    }

    @Test
    void shoppingVideoKey_sanitizesUnsafeCharacters() {
        String key = ObjectStorageKeys.shoppingVideoKeyAt(
                "cab/01", 1L, "sess id", "top", ".mp4", AT);
        assertEquals("videos/2026/07/13/cab_01/user-1/sess_id-top.mp4", key);
    }
}
