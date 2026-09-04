package com.aicabinet.device.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoorEventDeduplicatorTest {

    private final DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();

    @Test
    void duplicateWithinWindow_isDetected() {
        assertFalse(deduplicator.isDuplicate("S1", "OPEN"));
        assertTrue(deduplicator.isDuplicate("S1", "OPEN"));
        assertFalse(deduplicator.isDuplicate("S1", "CLOSED"));
    }

    @Test
    void differentSessions_notDuplicate() {
        assertFalse(deduplicator.isDuplicate("S1", "OPEN"));
        assertFalse(deduplicator.isDuplicate("S2", "OPEN"));
    }

    @Test
    void clear_allowsRetryAfterFailure() {
        assertFalse(deduplicator.isDuplicate("S1", "CLOSED", "seq:1"));
        assertTrue(deduplicator.isDuplicate("S1", "CLOSED", "seq:1"));
        deduplicator.clear("S1", "CLOSED", "seq:1");
        assertFalse(deduplicator.isDuplicate("S1", "CLOSED", "seq:1"));
    }
}
