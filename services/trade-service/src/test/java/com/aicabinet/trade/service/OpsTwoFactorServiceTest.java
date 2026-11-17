package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.TwoFactorEnrollDto;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.TotpService;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.domain.OpsTwoFactorRecoveryCode;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsTwoFactorRecoveryCodeMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    static {
        // M01：LambdaUpdateWrapper 需要 TableInfo 缓存；单跑本类时无其他测试先行初始化（2026-10-01 全量回归抓出）
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.aicabinet.trade.domain.UserInfo.class);
    }

    private static final long OPERATOR_ID = 1900000001L;
    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static final String JWT_SECRET = "ai-cabinet-test-secret-key-32bytes!!";

    @Mock private UserInfoMapper userInfoRepository;
    @Mock private OpsTwoFactorRecoveryCodeMapper recoveryRepository;
    @Mock private TotpService totpService;
    @Mock private JwtService jwtService;
    @Mock private AuthService authService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private com.aicabinet.trade.auth.LoginThrottleService loginThrottleService;

    private OpsTwoFactorService service;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        service = new OpsTwoFactorService(userInfoRepository, recoveryRepository,
                totpService, jwtService, authService, distributedLockService,
                new AuthProperties(JWT_SECRET, 3600L, false, false, 5, 15, null, AuthProperties.DEFAULT_REFRESH_GRACE_SECONDS),
                org.mockito.Mockito.mock(com.aicabinet.trade.service.AdminAuditService.class),
                loginThrottleService);
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

    /**
     * MA-320（2026-11-17 对抗验证轮）：修前 {@code /admin-2fa/verify} 没有任何账号维度失败计数——
     * 密码过了（challenge token 是密码登录发的）就能对着 6 位 TOTP 无限试，2FA 这道
     * 「账号接管最后一道门」形同虚设。现在复用 LoginThrottleService 的同一份预算。
     */
    @Test
    void verifyChallenge_wrongCode_recordsThrottleFailure() {
        UserInfo user = operator(true, SECRET);
        when(jwtService.validateChallengeToken("CHAL")).thenReturn(OPERATOR_ID);
        when(userInfoRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.verify(SECRET, "000000")).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> service.verifyChallenge("CHAL", "000000"));

        verify(loginThrottleService).recordFailure("13900000001");
    }

    @Test
    void verifyChallenge_lockedAccount_rejectedWithoutTryingTotp() {
        UserInfo user = operator(true, SECRET);
        when(jwtService.validateChallengeToken("CHAL")).thenReturn(OPERATOR_ID);
        when(userInfoRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(loginThrottleService.isLocked("13900000001")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.verifyChallenge("CHAL", "123456"));

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode(),
                "已锁定必须在试码之前拒绝，而不是又给一次穷举机会");
        verify(totpService, org.mockito.Mockito.never())
                .verify(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(authService, org.mockito.Mockito.never())
                .finalizeTwoFactorLogin(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void verifyChallenge_success_clearsThrottleFailures() {
        UserInfo user = operator(true, SECRET);
        when(jwtService.validateChallengeToken("CHAL")).thenReturn(OPERATOR_ID);
        when(userInfoRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(user));
        when(totpService.verify(SECRET, "654321")).thenReturn(true);
        when(authService.finalizeTwoFactorLogin(OPERATOR_ID))
                .thenReturn(new LoginResponse("T", OPERATOR_ID, 3600, 1L, true, false));

        service.verifyChallenge("CHAL", "654321");

        verify(loginThrottleService).clearFailures("13900000001");
    }

    @Test
    void verifyRecovery_shouldMarkUsedAndFinalize() {
        UserInfo user = operator(true, SECRET);
        when(jwtService.validateChallengeToken("CHAL")).thenReturn(OPERATOR_ID);
        when(userInfoRepository.findByIdForUpdate(OPERATOR_ID)).thenReturn(Optional.of(user));
        String code = "ABCDE-FGHJK-LMNPQ";
        OpsTwoFactorRecoveryCode row = new OpsTwoFactorRecoveryCode();
        row.setUserId(OPERATOR_ID);
        row.setCodeHash(hmacSha256(OPERATOR_ID + ":" + code.replace("-", "")));
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

    private static String hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
