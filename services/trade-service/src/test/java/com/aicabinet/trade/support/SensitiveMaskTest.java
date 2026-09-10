package com.aicabinet.trade.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aicabinet.common.util.SensitiveMask;
import org.junit.jupiter.api.Test;

class SensitiveMaskTest {

    @Test
    void idCard_masksMiddle() {
        assertEquals("110***********1234", SensitiveMask.idCard("110101199001011234"));
    }

    @Test
    void idCard_shortReturnsStars() {
        assertEquals("***", SensitiveMask.idCard("1234567"));
    }

    @Test
    void idCard_nullSafe() {
        assertNull(SensitiveMask.idCard(null));
    }

    @Test
    void bankCard_keepsLast4() {
        assertEquals("****7890", SensitiveMask.bankCard("6222021234567890"));
    }

    @Test
    void bankCard_stripsSpaces() {
        assertEquals("****7890", SensitiveMask.bankCard("6222 0212 3456 7890"));
    }
}
