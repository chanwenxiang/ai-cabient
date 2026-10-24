package com.aicabinet.trade.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A2（lessons #97/#225）：分账回退单号必须由调用方持久化后传入。 */
class WeChatProfitSharingReturnNoTest {

    @Test
    void returnNoMustBeProvided() {
        assertEquals("PSR-1", WeChatProfitSharingService.requireStableReturnNo(" PSR-1 "));
        assertThrows(IllegalArgumentException.class,
                () -> WeChatProfitSharingService.requireStableReturnNo(null));
        assertThrows(IllegalArgumentException.class,
                () -> WeChatProfitSharingService.requireStableReturnNo("   "));
    }
}
