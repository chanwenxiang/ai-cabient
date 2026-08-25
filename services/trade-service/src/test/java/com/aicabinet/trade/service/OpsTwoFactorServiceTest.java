package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.TwoFactorEnrollDto;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.TotpService;
import com.aicabinet.trade.domain.OpsTwoFactorRecoveryCode;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsTwoFactorRecoveryCodeMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsTwoFactorServiceTest {

    private static final long OPERATOR_ID = 1900000001L;
    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private OpsTwoFactorRecoveryCodeMapper recoveryRepository;
    @Mock private TotpService totpService;
    @Mock private JwtService jwtService;
    @Mock private AuthService authService;
    @Mock private DistributedLockService distributedLockService;

    private OpsTwoFactorService service;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        service = new OpsTwoFactorService(userInfoRepository, recoveryRepository,
                totpService, jwtService, authService, distributedLockService);
    }

    private UserInfo operator(boolean enabled, String secret) {
        UserInfo u = new UserInfo();
        u.setUserId(OPERATOR_ID);
        u.setPhoneNumber("13900000001");
        u.setTotpEnabled(enabled);
        u.setTotpSecret(secret);
        return u;
    }

    @Test
    void enroll_shouldGenerateSecretAndRecoveryCodes() {
        UserInfo user = operator(false, null);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.generateSecret()).thenReturn(SECRET);
        when(totpService.otpauthUri(SECRET, "13900000001")).thenReturn("otpauth://totp/x?secret=" + SECRET);

        TwoFactorEnrollDto dto = service.enroll(OPERATOR_ID);

        assertEquals(SECRET, dto.secret());
        assertEquals(8, dto.recoveryCodes().size());
        assertTrue(dto.recoveryCodes().stream().allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}-[A-Z2-9]{5}")));
        ArgumentCaptor<OpsTwoFactorRecoveryCode> captor = ArgumentCaptor.forClass(OpsTwoFactorRecoveryCode.class);
        verify(recoveryRepository, org.mockito.Mockito.times(8)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(OpsTwoFactorRecoveryCode::isUsed));
    }

    @Test
    void confirm_shouldEnableWhenCodeValid() {
        UserInfo user = operator(false, SECRET);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.verify(SECRET, "123456")).thenReturn(true);

        service.confirm(OPERATOR_ID, "123456");

        assertTrue(user.isTotpEnabled());
        verify(userInfoRepository).save(user);
    }

    @Test
    void confirm_shouldRejectInvalidCode() {
        UserInfo user = operator(false, SECRET);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.verify(SECRET, "000000")).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> service.confirm(OPERATOR_ID, "000000"));
        assertFalse(user.isTotpEnabled());
    }

    @Test
    void verifyChallenge_shouldFinalizeLoginOnValidCode() {
        UserInfo user = operator(true, SECRET);
        when(jwtService.validateChallengeToken("CHAL")).thenReturn(OPERATOR_ID);
        when(userInfoRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.verify(SECRET, "123456")).thenReturn(true);
        LoginResponse expected = new LoginResponse("T", OPERATOR_ID, 3600, 1L, true, false);
        when(authService.finalizeTwoFactorLogin(OPERATOR_ID)).thenReturn(expected);

        LoginResponse out = service.verifyChallenge("CHAL", "123456");

        assertEquals(expected, out);
    }

    @Test
    void verifyRecovery_shouldMarkUsedAndFinalize() {
        UserInfo user = operator(true, SECRET);
        when(jwtService.validateChallengeToken("CHAL")).thenReturn(OPERATOR_ID);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(user));
        String code = "ABCDE-FGHJK-LMNPQ";
        OpsTwoFactorRecoveryCode row = new OpsTwoFactorRecoveryCode();
        row.setUserId(OPERATOR_ID);
        row.setCodeHash(sha256(OPERATOR_ID + ":" + code.replace("-", "")));
        row.setUsed(false);
        when(recoveryRepository.findByUserId(OPERATOR_ID)).thenReturn(List.of(row));
        when(authService.finalizeTwoFactorLogin(OPERATOR_ID)).thenReturn(
                new LoginResponse("T", OPERATOR_ID, 3600, 1L, true, false));

        LoginResponse out = service.verifyRecovery("CHAL", code);

        assertNotNull(out);
        assertTrue(row.isUsed());
        verify(recoveryRepository).updateById(row);
    }

    @Test
    void disable_shouldClearSecretWhenCodeValid() {
        UserInfo user = operator(true, SECRET);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.verify(SECRET, "123456")).thenReturn(true);

        service.disable(OPERATOR_ID, "123456");

        assertFalse(user.isTotpEnabled());
        assertEquals(null, user.getTotpSecret());
        verify(recoveryRepository).deleteByUserId(OPERATOR_ID);
    }

    @Test
    void enroll_shouldRejectWhenAlreadyEnabled() {
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(operator(true, SECRET)));

        assertThrows(ResponseStatusException.class, () -> service.enroll(OPERATOR_ID));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
