package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayIdsTest {

    @Test
    void stripHashIdMarkers_removesIdHash() {
        assertEquals("商户要货 3 · 719325384517",
                DisplayIds.stripHashIdMarkers("商户要货 #3 · 719325384517"));
        assertEquals("审批提醒：补货任务 16",
                DisplayIds.stripHashIdMarkers("审批提醒：补货任务 #16"));
    }
}
