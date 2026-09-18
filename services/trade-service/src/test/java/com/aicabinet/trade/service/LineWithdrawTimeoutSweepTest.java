package com.aicabinet.trade.service;

import com.aicabinet.trade.config.LineWithdrawProperties;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.LineWalletAccount;
import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** H38/H26：线长提现 PAYING 超时兜底置 FAILED 并解冻；打款失败同样释放冻结。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineWithdrawTimeoutSweepTest {

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
        service = new LineWithdrawService(withdrawMapper, managerMapper, deviceMapper,
                lineManagerService, lineWalletService, payoutService,
                new LineWithdrawProperties(true, 100, 500_000, 50_000, 0, 0),
                permissionService, auditService, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void failStalePayingWithdraws_marksFailedAndReleases() {
        LineWithdrawRequest stale = new LineWithdrawRequest();
        stale.setRequestId(55L);
        stale.setManagerId(8L);
        stale.setAmountCents(10_000L);
        stale.setStatus("PAYING");
        when(withdrawMapper.findByStatusAndUpdatedAtBefore(eq("PAYING"), any())).thenReturn(List.of(stale));
        when(withdrawMapper.findById(55L)).thenReturn(Optional.of(stale));

        assertEquals(1, service.failStalePayingWithdraws());

        assertEquals("FAILED", stale.getStatus());
        verify(lineWalletService).releaseFrozen(eq(8L), eq(10_000L), eq("WITHDRAW"), eq("55"), anyString());
    }

    @Test
    void payoutFailure_releasesFrozen() {
        LineManager manager = new LineManager();
        manager.setManagerId(8L);
        LineWithdrawRequest request = new LineWithdrawRequest();
        request.setRequestId(56L);
        request.setManagerId(8L);
        request.setAmountCents(10_000L);
        request.setStatus("APPROVED");
        when(withdrawMapper.findById(56L)).thenReturn(Optional.of(request));
        when(lineManagerService.requireManager(8L)).thenReturn(manager);
        when(payoutService.payout(any(), eq(manager))).thenReturn(
                LineWithdrawPayoutService.PayoutResult.failure("WECHAT", null, "渠道失败"));

        service.executePayout(56L);

        assertEquals("FAILED", request.getStatus());
        verify(lineWalletService).releaseFrozen(eq(8L), eq(10_000L), eq("WITHDRAW"), eq("56"), anyString());
        verify(lineWalletService, never()).consumeFrozen(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }
}
