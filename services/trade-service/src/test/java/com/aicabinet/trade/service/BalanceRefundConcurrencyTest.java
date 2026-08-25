package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.mapper.BalanceRefundAllocationMapper;
import com.aicabinet.trade.mapper.BalanceRefundRequestMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceRefundConcurrencyTest {

    @Mock private BalanceRefundRequestMapper requestMapper;
    @Mock private BalanceRefundAllocationMapper allocationMapper;
    @Mock private UserAccountMapper accountMapper;
    @Mock private RechargeOrderMapper rechargeOrderMapper;
    @Mock private PaymentService paymentService;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private BalanceRefundService service;

    @BeforeEach
    void setUp() {
        service = new BalanceRefundService(
                requestMapper, allocationMapper, accountMapper, rechargeOrderMapper,
                paymentService, balanceLedgerService, permissionService, auditService,
                distributedLockService, null);
    }

    @Test
    void apply_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(BalanceRefundService.balanceRefundLockKey(10001L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.apply(10001L, 500, "test"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void apply_acquiresLockAndFreezesBalance() {
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(10_000);
        account.setFrozenCents(0);

        when(distributedLockService.tryLock(
                eq(BalanceRefundService.balanceRefundLockKey(10001L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(requestMapper.countByUserIdAndStatus(10001L, "PENDING_REVIEW")).thenReturn(0L);
        when(accountMapper.findByIdForUpdate(10001L)).thenReturn(Optional.of(account));
        when(rechargeOrderMapper.findRefundablePaidByUser(10001L)).thenReturn(Collections.emptyList());

        assertThrows(ResponseStatusException.class, () -> service.apply(10001L, 500, "test"));

        verify(distributedLockService).unlock(BalanceRefundService.balanceRefundLockKey(10001L));
    }
}
