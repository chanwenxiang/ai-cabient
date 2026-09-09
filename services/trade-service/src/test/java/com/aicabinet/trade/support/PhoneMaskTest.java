package com.aicabinet.trade.support;

import com.aicabinet.common.util.PhoneMask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoneMaskTest {

    @Test
    void mask_masksMiddleDigits() {
        assertEquals("139****0001", PhoneMask.mask("13900000001"));
    }

    @Test
    void mask_shortPhone_returnsStars() {
        assertEquals("***", PhoneMask.mask("123"));
    }

    @Test
    void mask_null_passthrough() {
        assertNull(PhoneMask.mask(null));
    }
}
