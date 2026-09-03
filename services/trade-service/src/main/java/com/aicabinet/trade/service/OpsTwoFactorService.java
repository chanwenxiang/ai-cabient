package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.TwoFactorStatusDto;
import com.aicabinet.common.dto.TwoFactorEnrollDto;
import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.TotpService;
import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.domain.OpsTwoFactorRecoveryCode;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsTwoFactorRecoveryCodeMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 运营账号双因子认证（TOTP）：绑定、确认、登录校验、后备码、关闭。
 */
@Service
public class OpsTwoFactorService {

    private static final int RECOVERY_CODE_COUNT = 8;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserInfoMapper userInfoRepository;
    private final OpsTwoFactorRecoveryCodeMapper recoveryRepository;
    private final TotpService totpService;
    private final JwtService jwtService;
    private final AuthService authService;
    private final DistributedLockService distributedLockService;
    private final byte[] recoveryPepper;

    public OpsTwoFactorService(UserInfoMapper userInfoRepository,
                               OpsTwoFactorRecoveryCodeMapper recoveryRepository,
                               TotpService totpService,
                               JwtService jwtService,
                               AuthService authService,
                               DistributedLockService distributedLockService,
                               AuthProperties authProperties) {
        this.userInfoRepository = userInfoRepository;
        this.recoveryRepository = recoveryRepository;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.authService = authService;
        this.distributedLockService = distributedLockService;
        String secret = authProperties == null ? null : authProperties.jwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("aicabinet.auth.jwt-secret required for 2FA recovery hash");
        }
        this.recoveryPepper = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public TwoFactorEnrollDto enroll(Long operatorId) {
        return runWithTwoFactorLock(operatorId, () -> doEnroll(operatorId));
    }

    private TwoFactorEnrollDto doEnroll(Long operatorId) {
        UserInfo user = requireOperatorUserForUpdate(operatorId);
        if (user.isTotpEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "双因子认证已启用，请先关闭后重新绑定");
        }
        if (user.getTotpSecret() == null || user.getTotpSecret().isBlank()) {
            user.setTotpSecret(totpService.generateSecret());
            userInfoRepository.save(user);
        }
        recoveryRepository.deleteByUserId(operatorId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = formatRecoveryCode();
            OpsTwoFactorRecoveryCode row = new OpsTwoFactorRecoveryCode();
            row.setUserId(operatorId);
            row.setCodeHash(hashRecoveryCode(operatorId, normalizeRecoveryCode(code)));
            row.setUsed(false);
            row.setCreatedAt(Instant.now());
            recoveryRepository.insert(row);
            codes.add(code);
        }
        return new TwoFactorEnrollDto(
                user.getTotpSecret(),
                totpService.otpauthUri(user.getTotpSecret(), user.getPhoneNumber()),
                codes);
    }

    @Transactional(readOnly = true)
    public TwoFactorStatusDto status(Long operatorId) {
        UserInfo user = requireOperatorUser(operatorId);
        return new TwoFactorStatusDto(user.isTotpEnabled());
    }

    @Transactional
    public void confirm(Long operatorId, String code) {
        runWithTwoFactorLock(operatorId, () -> {
            doConfirm(operatorId, code);
            return null;
        });
    }

    private void doConfirm(Long operatorId, String code) {
        UserInfo user = requireOperatorUserForUpdate(operatorId);
        if (user.isTotpEnabled()) {
            return;
        }
        if (user.getTotpSecret() == null || !totpService.verify(user.getTotpSecret(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "动态码不正确");
        }
        user.setTotpEnabled(true);
        userInfoRepository.save(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse verifyChallenge(String challengeToken, String code) {
        Long userId = jwtService.validateChallengeToken(challengeToken);
        UserInfo user = requireOperatorUser(userId);
        if (user.isTotpEnabled() && totpService.verify(user.getTotpSecret(), code)) {
            return authService.finalizeTwoFactorLogin(userId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "动态码不正确");
    }

    @Transactional
    public LoginResponse verifyRecovery(String challengeToken, String recoveryCode) {
        Long userId = jwtService.validateChallengeToken(challengeToken);
        return runWithTwoFactorLock(userId, () -> doVerifyRecovery(userId, recoveryCode));
    }

    private LoginResponse doVerifyRecovery(Long userId, String recoveryCode) {
        requireOperatorUserForUpdate(userId);
        String hash = hashRecoveryCode(userId, normalizeRecoveryCode(recoveryCode));
        OpsTwoFactorRecoveryCode row = recoveryRepository.findByUserId(userId).stream()
                .filter(r -> !r.isUsed() && constantTimeEquals(r.getCodeHash(), hash))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "后备码不正确或已使用"));
        row.setUsed(true);
        recoveryRepository.updateById(row);
        return authService.finalizeTwoFactorLogin(userId);
    }

    @Transactional
    public void disable(Long operatorId, String code) {
        runWithTwoFactorLock(operatorId, () -> {
            doDisable(operatorId, code);
            return null;
        });
    }

    private void doDisable(Long operatorId, String code) {
        UserInfo user = requireOperatorUserForUpdate(operatorId);
        if (!user.isTotpEnabled()) {
            return;
        }
        if (!totpService.verify(user.getTotpSecret(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "动态码不正确");
        }
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        userInfoRepository.save(user);
        recoveryRepository.deleteByUserId(operatorId);
    }

    private UserInfo requireOperatorUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (userId < CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅运营账号可使用双因子认证");
        }
        return user;
    }

    private UserInfo requireOperatorUserForUpdate(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        UserInfo user = userInfoRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (userId < CabinetConstants.OPERATOR_USER_ID_START) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅运营账号可使用双因子认证");
        }
        return user;
    }

    static String opsTwoFactorLockKey(Long userId) {
        return "ops:2fa:" + userId;
    }

    private <T> T runWithTwoFactorLock(Long userId, java.util.function.Supplier<T> action) {
        String key = opsTwoFactorLockKey(userId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "双因子认证处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private String formatRecoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i > 0 && i % 5 == 0) {
                sb.append('-');
            }
            sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String normalizeRecoveryCode(String code) {
        return code == null ? "" : code.toUpperCase().replace("-", "").replace(" ", "").trim();
    }

    /** HMAC-SHA256(jwt-secret, userId:code)，服务端 pepper，避免仅靠 userId 的可预测哈希。 */
    private String hashRecoveryCode(Long userId, String normalizedCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(recoveryPepper, "HmacSHA256"));
            byte[] digest = mac.doFinal((userId + ":" + normalizedCode).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
