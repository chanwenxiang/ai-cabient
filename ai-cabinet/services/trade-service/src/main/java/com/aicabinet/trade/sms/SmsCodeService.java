package com.aicabinet.trade.sms;

import com.aicabinet.trade.config.AuthProperties;
import com.aicabinet.trade.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final AuthProperties authProperties;
    private final SecurityProperties securityProperties;
    private final WebhookSmsSender webhookSmsSender;

    public SmsCodeService(AuthProperties authProperties,
                          SecurityProperties securityProperties,
                          WebhookSmsSender webhookSmsSender) {
        this.authProperties = authProperties;
        this.securityProperties = securityProperties;
        this.webhookSmsSender = webhookSmsSender;
    }

    public void sendCode(String phoneNumber) {
        String code = generateCode();
        Instant expiresAt = Instant.now().plusSeconds(authProperties.sms().ttlSeconds());
        codes.put(phoneNumber, new CodeEntry(code, expiresAt));
        if (securityProperties.mockEnabled()) {
            log.info("DEV SMS code for {}: {}", maskPhone(phoneNumber), code);
            return;
        }
        webhookSmsSender.send(phoneNumber, code);
    }

    public boolean verifyCode(String phoneNumber, String code) {
        // dev：允许直接使用配置的 mock 验证码，便于本地联调（prod 走严格校验）
        if (securityProperties.mockEnabled()
                && authProperties.sms().mockCode().equals(code)) {
            codes.remove(phoneNumber);
            return true;
        }
        CodeEntry entry = codes.get(phoneNumber);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            codes.remove(phoneNumber);
            return false;
        }
        if (!entry.code().equals(code)) {
            return false;
        }
        codes.remove(phoneNumber);
        return true;
    }

    private String generateCode() {
        if (securityProperties.mockEnabled()) {
            return authProperties.sms().mockCode();
        }
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private record CodeEntry(String code, Instant expiresAt) {}
}
