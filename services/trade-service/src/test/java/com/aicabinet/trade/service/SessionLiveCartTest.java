package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.common.dto.LiveCartUpdateRequest;
import org.springframework.web.server.ResponseStatusException;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionLiveCartTest {

    @Mock ShoppingSessionMapper repository;
    @Mock DistributedLockService distributedLockService;
    @Mock SessionService sessionService;

    SessionLiveCartService liveCartService;

    @BeforeEach
    void setUp() {
        liveCartService = new SessionLiveCartService(
                repository, null, null, new ObjectMapper(), sessionService);
        org.mockito.Mockito.lenient().when(sessionService.runWithSessionLifeLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(1);
            return action.get();
        });
    }

    @Test
    void liveCart_deviceMismatch_rejected() {
        // AU-192（批次 9）：internal live-cart 强制设备绑定——上报 deviceId ≠ 会话归属柜机
        // 即 400 DEVICE_MISMATCH（未传也拒，防识别侧被攻陷后向任意会话注入展示项）
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S1");
        session.setUserId(1L);
        session.setDeviceId("CAB-001");
        session.setState(SessionState.SHOPPING);
        when(repository.findByIdForUpdate("S1")).thenReturn(Optional.of(session));

        var noDevice = assertThrows(ResponseStatusException.class,
                () -> liveCartService.updateLiveCartFromVision("S1", new LiveCartUpdateRequest(
                        "REPLACE", List.of(), null)));
        assertEquals(400, noDevice.getStatusCode().value());
        assertEquals(ApiMessages.DEVICE_MISMATCH, noDevice.getReason());

        var wrongDevice = assertThrows(ResponseStatusException.class,
                () -> liveCartService.updateLiveCartFromVision("S1", new LiveCartUpdateRequest(
                        "REPLACE", List.of(), "CAB-999")));
        assertEquals(400, wrongDevice.getStatusCode().value());
        assertEquals(ApiMessages.DEVICE_MISMATCH, wrongDevice.getReason());

        verify(repository, never()).save(any());
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

        var first = liveCartService.updateLiveCartFromVision("S1", new LiveCartUpdateRequest(
                "REPLACE",
                List.of(new LiveCartUpdateRequest.LiveCartItem("SKU-A", "A", 2, 350)), "CAB-001"));
        assertEquals(2, first.totalQty());
        assertEquals(700, first.totalAmountCents());

        var second = liveCartService.updateLiveCartFromVision("S1", new LiveCartUpdateRequest(
                "DELTA",
                List.of(new LiveCartUpdateRequest.LiveCartItem("SKU-A", "A", -1, 350)), "CAB-001"));
        assertEquals(1, second.totalQty());
        assertEquals(350, second.totalAmountCents());
    }
}
