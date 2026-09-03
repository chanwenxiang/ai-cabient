package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.identity.IdentityVerifyClient;
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
class AccountConcurrencyTest {

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private PayScoreService payScoreService;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private IdentityVerifyClient identityVerifyClient;

    private AccountService service;

    @BeforeEach
    void setUp() {
        service = new AccountService(userInfoRepository, userAccountRepository,
                payScoreService, balanceLedgerService, distributedLockService, identityVerifyClient, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void bindWxOpenId_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                AccountService.userAccountLockKey(100L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.bindWxOpenId(100L, "openid"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void bindWxOpenId_whenUserMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                AccountService.userAccountLockKey(101L), 60L, 5L))
                .thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.bindWxOpenId(101L, "openid"));

        verify(distributedLockService).unlock(AccountService.userAccountLockKey(101L));
    }
}
