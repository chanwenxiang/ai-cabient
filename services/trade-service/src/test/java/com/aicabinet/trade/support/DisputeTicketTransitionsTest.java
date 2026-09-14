package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisputeTicketTransitionsTest {

    @Test
    void open_allowsClaimResolveReply() {
        assertTrue(DisputeTicketTransitions.canActWhileOpen("OPEN"));
        assertTrue(DisputeTicketTransitions.canActWhileOpen("open"));
        assertFalse(DisputeTicketTransitions.canActWhileOpen("RESOLVED"));
        assertFalse(DisputeTicketTransitions.canActWhileOpen("CLOSED"));
        assertFalse(DisputeTicketTransitions.canActWhileOpen(null));
    }

    @Test
    void resolved_only_canClose() {
        assertTrue(DisputeTicketTransitions.canClose("RESOLVED"));
        assertFalse(DisputeTicketTransitions.canClose("OPEN"));
        assertFalse(DisputeTicketTransitions.canClose("CLOSED"));
    }

    @Test
    void resolvedOrClosed_canReopen() {
        assertTrue(DisputeTicketTransitions.canReopen("RESOLVED"));
        assertTrue(DisputeTicketTransitions.canReopen("CLOSED"));
        assertFalse(DisputeTicketTransitions.canReopen("OPEN"));
        assertTrue(DisputeTicketTransitions.canReopenForRefundFlow("closed"));
        assertTrue(DisputeTicketTransitions.blocksConsumerFile("RESOLVED"));
        assertFalse(DisputeTicketTransitions.blocksConsumerFile("OPEN"));
    }
}
