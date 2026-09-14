package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.ShoppingSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionLogContextTest {

    @Test
    void of_session_includesIds() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("s-1");
        session.setDeviceId("d-9");
        session.setUserId(42L);
        assertEquals("sessionId=s-1 deviceId=d-9 userId=42", SessionLogContext.of(session));
    }

    @Test
    void of_nulls_useDash() {
        assertEquals("sessionId=- deviceId=- userId=-", SessionLogContext.of(null));
        assertEquals("sessionId=- deviceId=- userId=-", SessionLogContext.of(null, " ", null));
    }
}
