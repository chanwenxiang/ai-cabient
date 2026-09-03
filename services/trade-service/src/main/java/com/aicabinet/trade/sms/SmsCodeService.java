package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.SmsVerificationCode;
import com.aicabinet.trade.mapper.SmsVerificationCodeMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 同一手机号发送冷却 */
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    /** 同一手机号每小时最多发送次数 */
    private static final int SEND_MAX_PER_HOUR = 5;
    private static final Duration SEND_HOUR_WINDOW = Duration.ofHours(1);
    /** 同一验证码错误次数上限后作废 */
    private static final int VERIFY_MAX_FAILURES = 5;
    private static final Duration VERIFY_FAIL_TTL = Duration.ofMinutes(10);

    private static final String SEND_COOLDOWN_KEY = "aicabinet:sms:send:cd:";
    private static final String SEND_HOUR_KEY = "aicabinet:sms:send:hr:";
    private static final String VERIFY_FAIL_KEY = "aicabinet:sms:verify:fail:";

    private final AuthProperties authProperties;
    private final SecurityProperties securityProperties;
    private final SmsSender smsSender;
    private final SmsVerificationCodeMapper codeRepository;
    private final RedissonClient redisson;

    public SmsCodeService(AuthProperties authProperties,
                          SecurityProperties securityProperties,
                          SmsSender smsSender,
                          SmsVerificationCodeMapper codeRepository,
                          RedissonClient redisson) {
        this.authProperties = authProperties;
        this.securityProperties = securityProperties;
        this.smsSender = smsSender;
        this.codeRepository = codeRepository;
        this.redisson = redisson;
    }

    @Transactional
    public void sendCode(String phoneNumber) {
        String normalized = normalizePhone(phoneNumber);
        assertSendAllowed(normalized);
        String code = generateCode();
        Instant expiresAt = Instant.now().plusSeconds(authProperties.sms().ttlSeconds());
        persistCode(normalized, code, expiresAt);
        markSendSucceeded(normalized);

        if (securityProperties.mockEnabled()) {
            String masked = maskPhone(normalized);
            log.info("DEV SMS code for {}: {} (stored in DB)", masked, code);
            return;
        }
        smsSender.send(normalized, code);
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
            clearVerifyFailures(normalized);
            return true;
        }

        Optional<SmsVerificationCode> latest = codeRepository
                .findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(normalized);
        if (latest.isEmpty()) {
            recordVerifyFailure(normalized);
            return false;
        }
        SmsVerificationCode entry = latest.get();
        if (entry.getExpiresAt().isBefore(Instant.now())) {
            entry.setUsedAt(Instant.now());
            codeRepository.save(entry);
            recordVerifyFailure(normalized);
            return false;
        }
        if (!entry.getCode().equals(code)) {
            if (recordVerifyFailure(normalized) >= VERIFY_MAX_FAILURES) {
                entry.setUsedAt(Instant.now());
                codeRepository.save(entry);
                clearVerifyFailures(normalized);
                log.warn("sms code burned after too many failures phone={}", maskPhone(normalized));
            }
            return false;
        }
        entry.setUsedAt(Instant.now());
        codeRepository.save(entry);
        clearVerifyFailures(normalized);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<SmsCodeSnapshot> latestActiveCode(String phoneNumber) {
        String normalized = normalizePhone(phoneNumber);
        return codeRepository.findTopByPhoneNumberAndUsedAtIsNullOrderByCreatedAtDesc(normalized)
                .filter(e -> e.getExpiresAt().isAfter(Instant.now()))
                .map(e -> new SmsCodeSnapshot(e.getPhoneNumber(), e.getCode(), e.getExpiresAt()));
    }

    private void assertSendAllowed(String phone) {
        RBucket<String> cooldown = redisson.getBucket(SEND_COOLDOWN_KEY + phone, StringCodec.INSTANCE);
        if (cooldown.isExists()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiMessages.SMS_SEND_TOO_FREQUENT);
        }
        RAtomicLong hourly = redisson.getAtomicLong(SEND_HOUR_KEY + phone);
        long count = hourly.get();
        if (count >= SEND_MAX_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ApiMessages.SMS_SEND_LIMIT);
        }
    }

    private void markSendSucceeded(String phone) {
        redisson.getBucket(SEND_COOLDOWN_KEY + phone, StringCodec.INSTANCE)
                .set("1", SEND_COOLDOWN);
        RAtomicLong hourly = redisson.getAtomicLong(SEND_HOUR_KEY + phone);
        long n = hourly.incrementAndGet();
        if (n == 1) {
            hourly.expire(SEND_HOUR_WINDOW);
        }
    }

    private long recordVerifyFailure(String phone) {
        RAtomicLong counter = redisson.getAtomicLong(VERIFY_FAIL_KEY + phone);
        long attempts = counter.incrementAndGet();
        if (attempts == 1) {
            counter.expire(VERIFY_FAIL_TTL);
        }
        return attempts;
    }

    private void clearVerifyFailures(String phone) {
        redisson.getAtomicLong(VERIFY_FAIL_KEY + phone).delete();
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
