package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkuIdsTest {

    @Test
    void fromCode_isPlainDigits() {
        assertEquals("42", SkuIds.fromCode(42L));
        assertEquals("100172", SkuIds.fromCode(100172L));
    }

    @Test
    void isNumeric_rejectsPrefix() {
        assertTrue(SkuIds.isNumeric("1001"));
        assertFalse(SkuIds.isNumeric("SKU-1001"));
        assertFalse(SkuIds.isNumeric(""));
        assertFalse(SkuIds.isNumeric(null));
    }

    @Test
    void requireNumeric_rejectsLegacyId() {
        var ex = assertThrows(ResponseStatusException.class, () -> SkuIds.requireNumeric("SKU-DEMO-001"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.SKU_ID_NUMERIC, ex.getReason());
    }
}
