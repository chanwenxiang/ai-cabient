package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineWithdrawRequestDto;
import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineWithdrawConcurrencyTest {

    @Mock private LineWithdrawRequestMapper withdrawMapper;
    @Mock private LineManagerMapper managerMapper;
    @Mock private LineDeviceMapper deviceMapper;
    @Mock private LineManagerService lineManagerService;
    @Mock private LineWalletService lineWalletService;
    @Mock private LineWithdrawPayoutService payoutService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private LineWithdrawService service;

    @BeforeEach
    void setUp() {
        LineWithdrawProperties properties = new LineWithdrawProperties(true, 100, 500_000, 50_000);
        service = new LineWithdrawService(
                withdrawMapper, managerMapper, deviceMapper, lineManagerService,
                lineWalletService, payoutService, properties,
                permissionService, auditService, distributedLockService);
    }

    @Test
    void apply_whenLockBusy_rejectsWithConflict() {
        LineManager manager = new LineManager();
        manager.setManagerId(7L);
        manager.setManagerName("线长7");
        when(lineManagerService.requireManager(7L)).thenReturn(manager);
        when(distributedLockService.tryLock(
                eq(LineWithdrawService.lineWalletLockKey(7L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.apply(7L, 10_000L, "REQ-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void apply_acquiresLockAndFreezesBalance() {
        LineManager manager = new LineManager();
        manager.setManagerId(8L);
        manager.setManagerName("线长8");

        LineWalletAccount account = new LineWalletAccount();
        account.setManagerId(8L);
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        when(lineManagerService.requireManager(8L)).thenReturn(manager);
        when(distributedLockService.tryLock(
                eq(LineWithdrawService.lineWalletLockKey(8L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-2")).thenReturn(Optional.empty());
        when(deviceMapper.selectCount(any())).thenReturn(1L);
        when(lineWalletService.ensureAccount(8L)).thenReturn(account);
        when(withdrawMapper.sumAmountByManagerSince(eq(8L), any())).thenReturn(0L);
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            LineWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(99L);
            return 1;
        });
        when(payoutService.payout(any(), eq(manager))).thenReturn(
                new LineWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok"));

        LineWithdrawRequestDto dto = service.apply(8L, 10_000L, "REQ-2");

        assertEquals("PAID", dto.status());
        verify(lineWalletService).freezeForWithdraw(eq(8L), eq(10_000L), any(), any(), any());
        verify(distributedLockService).unlock(LineWithdrawService.lineWalletLockKey(8L));
    }
}
