package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.config.OpsMonitoringProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpsExceptionScannerServiceTest {
    @Test
    void scanReportsStuckSettlementSession() {
        ShoppingSessionMapper sessions = mock(ShoppingSessionMapper.class);
        OpsExceptionService exceptions = mock(OpsExceptionService.class);
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S1"); session.setDeviceId("D1"); session.setUserId(1L);
        session.setState(SessionState.SETTLING);
        setUpdatedAt(session, Instant.now().minusSeconds(600));
        when(sessions.findByStateAndOpenTimeBefore(any(), any(), anyInt())).thenReturn(List.of());
        when(sessions.findByStateAndUpdatedAtBefore(any(), any(), anyInt())).thenAnswer(invocation ->
                invocation.getArgument(0) == SessionState.SETTLING ? List.of(session) : List.of());
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        when(tasks.tryBegin(anyString(), anyLong())).thenReturn(true);
        SystemConfigService cfg = mock(SystemConfigService.class);
        when(cfg.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        var scanner = new OpsExceptionScannerService(sessions, exceptions,
                new OpsMonitoringProperties(true, 10, 5, 3, 3), tasks, cfg);

        scanner.scan();

        verify(exceptions).report(eq("SETTLEMENT_STUCK"), eq("CRITICAL"),
                eq(new OpsExceptionService.ExceptionReport.ExceptionRefs("D1", "S1", null, 1L)),
                eq("订单结算滞留"), contains("超过 3 分钟"));
    }

    private static void setUpdatedAt(ShoppingSession session, Instant value) {
        try {
            var field = ShoppingSession.class.getDeclaredField("updatedAt");
            field.setAccessible(true); field.set(session, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
