package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.SmsVerificationCode;
import com.aicabinet.trade.mapper.SmsVerificationCodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

@Component
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthProperties authProperties;
    private final SecurityProperties securityProperties;
    private final WebhookSmsSender webhookSmsSender;
    private final SmsVerificationCodeMapper codeRepository;

    public SmsCodeService(AuthProperties authProperties,
                          SecurityProperties securityProperties,
                          WebhookSmsSender webhookSmsSender,
                          SmsVerificationCodeMapper codeRepository) {
        this.authProperties = authProperties;
        this.securityProperties = securityProperties;
        this.webhookSmsSender = webhookSmsSender;
        this.codeRepository = codeRepository;
    }

    @Transactional
    public void sendCode(String phoneNumber) {
        String normalized = normalizePhone(phoneNumber);
        String code = generateCode();
        Instant expiresAt = Instant.now().plusSeconds(authProperties.sms().ttlSeconds());
        persistCode(normalized, code, expiresAt);

        if (securityProperties.mockEnabled()) {
            log.info("DEV SMS code for {}: {} (stored in DB)", maskPhone(normalized), code);
            return;
        }
        webhookSmsSender.send(normalized, code);
    }

    @Transactional
    public boolean verifyCode(String phoneNumber, String code) {
        String normalized = normalizePhone(phoneNumber);
        // dev：允许配置的万能码，便于旧脚本联调（mock-code 为空则只认 DB 中真实下发码）
        String mockCode = authProperties.sms().mockCode();
        if (securityProperties.mockEnabled()
                && mockCode != null && !mockCode.isBlank()
                && mockCode.equals(code)) {
            markLatestUsed(normalized);
            return true;
        }

        Optional<SmsVerificationCode> latest = codeRepository
                .findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(normalized);
        if (latest.isEmpty()) {
            return false;
        }
        SmsVerificationCode entry = latest.get();
        if (entry.getExpiresAt().isBefore(Instant.now())) {
            entry.setUsedAt(Instant.now());
            codeRepository.save(entry);
            return false;
        }
        if (!entry.getCode().equals(code)) {
            return false;
        }
        entry.setUsedAt(Instant.now());
        codeRepository.save(entry);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<SmsCodeSnapshot> latestActiveCode(String phoneNumber) {
        String normalized = normalizePhone(phoneNumber);
        return codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(normalized)
                .filter(e -> e.getExpiresAt().isAfter(Instant.now()))
                .map(e -> new SmsCodeSnapshot(e.getPhoneNumber(), e.getCode(), e.getExpiresAt()));
    }

    private void persistCode(String phoneNumber, String code, Instant expiresAt) {
        SmsVerificationCode row = new SmsVerificationCode();
        row.setPhoneNumber(phoneNumber);
        row.setCode(code);
        row.setExpiresAt(expiresAt);
        codeRepository.save(row);
    }

    private void markLatestUsed(String phoneNumber) {
        codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(phoneNumber)
                .ifPresent(entry -> {
                    entry.setUsedAt(Instant.now());
                    codeRepository.save(entry);
                });
    }

    private String generateCode() {
        if (securityProperties.mockEnabled()) {
            String mockCode = authProperties.sms().mockCode();
            if (mockCode != null && !mockCode.isBlank()) {
                return mockCode;
            }
        }
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String normalizePhone(String phoneNumber) {
        return phoneNumber != null ? phoneNumber.trim() : "";
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public record SmsCodeSnapshot(String phoneNumber, String code, Instant expiresAt) {}
}
