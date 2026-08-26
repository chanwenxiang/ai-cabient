package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LiveCartUpdateRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLiveCartTest {

    @Mock ShoppingSessionMapper repository;
    @Mock ObjectMapper objectMapper;
    @Mock DistributedLockService distributedLockService;

    SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(
                repository, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, distributedLockService);
        org.springframework.test.util.ReflectionTestUtils.setField(sessionService, "self", sessionService);
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(sessionService, "objectMapper", new ObjectMapper());
    }

    @Test
    void replaceThenDelta_updatesQty() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S1");
        session.setUserId(1L);
        session.setDeviceId("CAB-001");
        session.setState(SessionState.SHOPPING);
        when(repository.findByIdForUpdate("S1")).thenReturn(Optional.of(session));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var first = sessionService.updateLiveCartFromVision("S1", new LiveCartUpdateRequest(
                "REPLACE",
                List.of(new LiveCartUpdateRequest.LiveCartItem("SKU-A", "A", 2, 350))));
        assertEquals(2, first.totalQty());
        assertEquals(700, first.totalAmountCents());

        var second = sessionService.updateLiveCartFromVision("S1", new LiveCartUpdateRequest(
                "DELTA",
                List.of(new LiveCartUpdateRequest.LiveCartItem("SKU-A", "A", -1, 350))));
        assertEquals(1, second.totalQty());
        assertEquals(350, second.totalAmountCents());
    }
}
