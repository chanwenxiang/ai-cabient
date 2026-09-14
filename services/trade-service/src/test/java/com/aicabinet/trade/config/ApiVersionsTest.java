package com.aicabinet.trade.config;

import com.aicabinet.common.api.ApiVersions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVersionsTest {

    @Test
    void current_isV2() {
        assertEquals("v2", ApiVersions.CURRENT);
        assertEquals(2, ApiVersions.CURRENT_MAJOR);
        assertEquals("/api/v2", ApiVersions.PATH_PREFIX);
        assertTrue(ApiVersions.isSupported(2));
        assertFalse(ApiVersions.isSupported(3));
        assertFalse(ApiVersions.isSupported(1));
    }

    @Test
    void parsePublicApiMajor_extractsSegment() {
        assertEquals(2, ApiVersions.parsePublicApiMajor("/api/v2/ops/admin/stats").orElse(-1));
        assertEquals(3, ApiVersions.parsePublicApiMajor("/api/v3/future").orElse(-1));
        assertTrue(ApiVersions.parsePublicApiMajor("/internal/v1/x").isEmpty());
        assertTrue(ApiVersions.parsePublicApiMajor("/admin/index.html").isEmpty());
    }

    @Test
    void unsupportedMessage_mentionsCurrentPrefix() {
        assertTrue(ApiVersions.unsupportedMessage(3).contains("/api/v2/"));
    }
}
