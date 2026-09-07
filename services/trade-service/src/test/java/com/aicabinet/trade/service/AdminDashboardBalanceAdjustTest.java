package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.common.dto.AdjustBalanceRequest;
import com.aicabinet.trade.mapper.MemberMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserBlacklistMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardBalanceAdjustTest {

    @Mock private PermissionService permissionService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private MemberMapper memberRepository;
    @Mock private UserBlacklistMapper blacklistRepository;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private AdminAuditService auditService;
    @Mock private PaymentService paymentService;
    @Mock private RechargeOrderMapper rechargeOrderRepository;
    @Mock private DistributedLockService distributedLockService;

    private OpsMemberFinanceAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsMemberFinanceAdminService(
                permissionService, userInfoRepository, userAccountRepository,
                memberRepository, blacklistRepository, balanceLedgerService, auditService,
                paymentService, rechargeOrderRepository, distributedLockService);
    }

    @Test
    void userBalanceLockKey_facadeAliasesExtractedService() {
        assertEquals(
                OpsMemberFinanceAdminService.userBalanceLockKey(10001L),
                AdminDashboardService.userBalanceLockKey(10001L));
        assertEquals("user:balance:10001", OpsMemberFinanceAdminService.userBalanceLockKey(10001L));
    }

    @Test
    void adjustBalance_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                OpsMemberFinanceAdminService.userBalanceLockKey(10001L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adjustBalance(1L, 10001L,
                        new AdjustBalanceRequest(100, "test adjust", "key-1")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void adjustBalance_whenUserNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                OpsMemberFinanceAdminService.userBalanceLockKey(10001L), 60L, 5L))
                .thenReturn(true);
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.adjustBalance(1L, 10001L,
                        new AdjustBalanceRequest(100, "test adjust", "key-1")));

        verify(distributedLockService).unlock(OpsMemberFinanceAdminService.userBalanceLockKey(10001L));
    }

    @Test
    void adjustBalance_rejectsOperatorUserId_g4ConsumerBalanceOnly() {
        long operatorUserId = CabinetConstants.OPERATOR_USER_ID_START;

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.adjustBalance(1L, operatorUserId,
                        new AdjustBalanceRequest(100, "test adjust", "key-op")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
