package com.aicabinet.trade.security;

import com.aicabinet.common.security.CidrAllowlist;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CidrAllowlistTest {

    @Test
    void emptyListAllowsAll() {
        assertTrue(CidrAllowlist.isAllowed("8.8.8.8", List.of()));
        assertTrue(CidrAllowlist.isAllowed("8.8.8.8", null));
    }

    @Test
    void matchesPrivateRanges() {
        List<String> cidrs = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.1/32");
        assertTrue(CidrAllowlist.isAllowed("10.1.2.3", cidrs));
        assertTrue(CidrAllowlist.isAllowed("172.18.0.5", cidrs));
        assertTrue(CidrAllowlist.isAllowed("192.168.1.10", cidrs));
        assertTrue(CidrAllowlist.isAllowed("127.0.0.1", cidrs));
        assertTrue(CidrAllowlist.isAllowed("::1", cidrs));
        assertFalse(CidrAllowlist.isAllowed("8.8.8.8", cidrs));
    }

    @Test
    void exactHostWithoutPrefix() {
        assertTrue(CidrAllowlist.isAllowed("127.0.0.1", List.of("127.0.0.1")));
        assertFalse(CidrAllowlist.isAllowed("127.0.0.2", List.of("127.0.0.1")));
    }
}
