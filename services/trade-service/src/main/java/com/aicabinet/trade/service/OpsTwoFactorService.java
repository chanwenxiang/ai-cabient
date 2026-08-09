package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LoginResponse;
import com.aicabinet.common.dto.TwoFactorStatusDto;
import com.aicabinet.common.dto.TwoFactorEnrollDto;
import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.auth.JwtService;
import com.aicabinet.trade.auth.TotpService;
import com.aicabinet.trade.domain.OpsTwoFactorRecoveryCode;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.OpsTwoFactorRecoveryCodeMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
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

    public OpsTwoFactorService(UserInfoMapper userInfoRepository,
                               OpsTwoFactorRecoveryCodeMapper recoveryRepository,
                               TotpService totpService,
                               JwtService jwtService,
                               AuthService authService) {
        this.userInfoRepository = userInfoRepository;
        this.recoveryRepository = recoveryRepository;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.authService = authService;
    }

    @Transactional
    public TwoFactorEnrollDto enroll(Long operatorId) {
        UserInfo user = requireOperatorUser(operatorId);
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
        UserInfo user = requireOperatorUser(operatorId);
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
        UserInfo user = requireOperatorUser(userId);
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
        UserInfo user = requireOperatorUser(operatorId);
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

    private static String hashRecoveryCode(Long userId, String normalizedCode) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((userId + ":" + normalizedCode).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
