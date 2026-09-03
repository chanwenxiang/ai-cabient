package com.aicabinet.trade.service;

import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.TotpService;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.mapper.OpsTwoFactorRecoveryCodeMapper;
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
class OpsTwoFactorConcurrencyTest {

    private static final long OPERATOR_ID = 1900000001L;

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private OpsTwoFactorRecoveryCodeMapper recoveryRepository;
    @Mock private TotpService totpService;
    @Mock private JwtService jwtService;
    @Mock private AuthService authService;
    @Mock private DistributedLockService distributedLockService;

    private OpsTwoFactorService service;

    @BeforeEach
    void setUp() {
        service = new OpsTwoFactorService(userInfoRepository, recoveryRepository,
                totpService, jwtService, authService, distributedLockService,
                new AuthProperties("ai-cabinet-test-secret-key-32bytes!!", 3600L, false, false, 5, 15, null));
    }

    @Test
    void enroll_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                OpsTwoFactorService.opsTwoFactorLockKey(OPERATOR_ID), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.enroll(OPERATOR_ID));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void confirm_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                OpsTwoFactorService.opsTwoFactorLockKey(OPERATOR_ID), 60L, 5L))
                .thenReturn(true);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.confirm(OPERATOR_ID, "123456"));

        verify(distributedLockService).unlock(OpsTwoFactorService.opsTwoFactorLockKey(OPERATOR_ID));
    }
}
