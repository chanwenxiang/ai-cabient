package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicLegalServiceTest {

    @Test
    void helpHasFaqsAndHotline() {
        var help = new PublicLegalService().help();
        assertEquals("400-888-0018", help.supportPhone());
        assertEquals(8, help.faqs().size());
        assertTrue(help.faqs().get(0).q().contains("开门"));
    }

    @Test
    void policiesCoverFourTypes() {
        var policies = new PublicLegalService().policies();
        assertEquals(4, policies.size());
        assertEquals("agreement", policies.get(0).type());
        assertEquals("V1.2", policies.get(0).version());
        assertFalse(policies.get(0).sections().isEmpty());
        assertTrue(policies.stream().anyMatch(p -> "privacy".equals(p.type())));
        assertTrue(policies.stream().anyMatch(p -> "refund".equals(p.type())));
        assertTrue(policies.stream().anyMatch(p -> "billing".equals(p.type())));
    }
}
