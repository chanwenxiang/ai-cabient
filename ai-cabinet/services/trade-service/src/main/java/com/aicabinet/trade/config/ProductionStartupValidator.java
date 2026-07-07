package com.aicabinet.trade.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionStartupValidator.class);
    private static final String DEV_JWT_SECRET = "ai-cabinet-dev-secret-key-32bytes!!";
    private static final String DEV_INTERNAL_KEY = "dev-internal-key-change-me";

    private final Environment environment;
    private final SecurityProperties securityProperties;
    private final InternalApiProperties internalApiProperties;
    private final AuthProperties authProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final WeChatMiniAppProperties weChatMiniAppProperties;

    public ProductionStartupValidator(Environment environment,
                                      SecurityProperties securityProperties,
                                      InternalApiProperties internalApiProperties,
                                      AuthProperties authProperties,
                                      WeChatPayProperties weChatPayProperties,
                                      WeChatMiniAppProperties weChatMiniAppProperties) {
        this.environment = environment;
        this.securityProperties = securityProperties;
        this.internalApiProperties = internalApiProperties;
        this.authProperties = authProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateProductionConfig() {
        if (!isProdProfile()) {
            if (securityProperties.mockEnabled()) {
                log.warn("Running with mock-enabled=true (dev mode). Do not use in production.");
            }
            return;
        }
        if (securityProperties.mockEnabled()) {
            throw new IllegalStateException("Production profile cannot run with aicabinet.security.mock-enabled=true");
        }
        requireSecret(authProperties.jwtSecret(), DEV_JWT_SECRET, "JWT_SECRET / aicabinet.auth.jwt-secret");
        requireSecret(internalApiProperties.key(), DEV_INTERNAL_KEY, "INTERNAL_API_KEY / aicabinet.internal-api.key");
        if (!authProperties.sms().hasWebhook()) {
            throw new IllegalStateException("Production requires SMS webhook: aicabinet.auth.sms.webhook-url");
        }
        if (!weChatPayProperties.isConfigured()) {
            throw new IllegalStateException("Production requires WeChat Pay V3 configuration");
        }
        boolean hasStaticCert = weChatPayProperties.platformCert() != null
                && !weChatPayProperties.platformCert().isBlank();
        if (!hasStaticCert && !weChatPayProperties.platformCertAutoFetch()) {
            throw new IllegalStateException(
                    "Production requires WECHAT_PLATFORM_CERT or WECHAT_PLATFORM_CERT_AUTO_FETCH=true");
        }
        if (!weChatMiniAppProperties.isConfigured()) {
            throw new IllegalStateException("Production requires WeChat MiniApp configuration");
        }
        log.info("Production configuration validated");
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static void requireSecret(String actual, String forbiddenDefault, String name) {
        if (actual == null || actual.isBlank() || forbiddenDefault.equals(actual)) {
            throw new IllegalStateException("Production requires a strong " + name);
        }
        if (actual.length() < 32) {
            throw new IllegalStateException(name + " must be at least 32 characters");
        }
    }
}
