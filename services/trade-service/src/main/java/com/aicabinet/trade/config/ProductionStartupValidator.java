package com.aicabinet.trade.config;

import com.aicabinet.common.security.InternalApiProperties;
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
    private static final String DEV_VISION_KEY = "dev-vision-key-change-me";

    private final Environment environment;
    private final SecurityProperties securityProperties;
    private final StagingProperties stagingProperties;
    private final InternalApiProperties internalApiProperties;
    private final AuthProperties authProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final VisionApiProperties visionApiProperties;
    private final MinioProperties minioProperties;
    private final CorsProperties corsProperties;
    private final ProfitSharingProperties profitSharingProperties;
    private final PayScoreProperties payScoreProperties;
    private final ReconciliationProperties reconciliationProperties;
    private final CheckoutProperties checkoutProperties;
    private final LineWithdrawProperties lineWithdrawProperties;
    private final MerchantWithdrawProperties merchantWithdrawProperties;

    public ProductionStartupValidator(Environment environment,
                                      SecurityProperties securityProperties,
                                      StagingProperties stagingProperties,
                                      InternalApiProperties internalApiProperties,
                                      AuthProperties authProperties,
                                      WeChatPayProperties weChatPayProperties,
                                      WeChatMiniAppProperties weChatMiniAppProperties,
                                      VisionApiProperties visionApiProperties,
                                      MinioProperties minioProperties,
                                      CorsProperties corsProperties,
                                      ProfitSharingProperties profitSharingProperties,
                                      PayScoreProperties payScoreProperties,
                                      ReconciliationProperties reconciliationProperties,
                                      CheckoutProperties checkoutProperties,
                                      LineWithdrawProperties lineWithdrawProperties,
                                      MerchantWithdrawProperties merchantWithdrawProperties) {
        this.environment = environment;
        this.securityProperties = securityProperties;
        this.stagingProperties = stagingProperties;
        this.internalApiProperties = internalApiProperties;
        this.authProperties = authProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.visionApiProperties = visionApiProperties;
        this.minioProperties = minioProperties;
        this.corsProperties = corsProperties;
        this.profitSharingProperties = profitSharingProperties;
        this.payScoreProperties = payScoreProperties;
        this.reconciliationProperties = reconciliationProperties;
        this.checkoutProperties = checkoutProperties;
        this.lineWithdrawProperties = lineWithdrawProperties;
        this.merchantWithdrawProperties = merchantWithdrawProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateProductionConfig() {
        if (!isStrictProfile()) {
            warnNonStrictProfile();
            return;
        }
        rejectMockFlagsInStrictProfile();
        requireSecret(authProperties.jwtSecret(), DEV_JWT_SECRET, "JWT_SECRET / aicabinet.auth.jwt-secret");
        requireSecret(internalApiProperties.key(), DEV_INTERNAL_KEY, "INTERNAL_API_KEY / aicabinet.internal-api.key");
        requireSecret(visionApiProperties.key(), DEV_VISION_KEY, "VISION_API_KEY / aicabinet.vision-api.key");
        if (!authProperties.sms().hasWebhook()) {
            throw new IllegalStateException("Production/staging requires SMS webhook: aicabinet.auth.sms.webhook-url");
        }
        validateSmsMockCode();
        validateCookieSecurity();
        warnIfDefaultMinioCredentials();
        warnIfLocalhostCors();
        if (isStagingProfile()) {
            validateStagingProfile();
        } else {
            validateProductionProfile();
        }
        log.info("{} configuration validated", isStagingProfile() ? "Staging" : "Production");
    }

    private void warnNonStrictProfile() {
        if (securityProperties.mockEnabled()) {
            log.warn("Running with mock-enabled=true (dev/demo). Settlement mock is NOT production accuracy; "
                    + "mock/low-conf/gravity-mismatch routes to need_review.");
        }
        String visionMock = environment.getProperty("VISION_MOCK_ENABLED",
                environment.getProperty("MOCK_ENABLED", ""));
        if ("true".equalsIgnoreCase(visionMock) || visionMock.isBlank() && securityProperties.mockEnabled()) {
            log.warn("Vision mock likely enabled (VISION_MOCK_ENABLED/MOCK_ENABLED). "
                    + "Demo recognition must not be treated as production accuracy; use docker-compose.production.yml for go-live.");
        }
        if (checkoutProperties.balanceOnly()) {
            log.info("CHECKOUT_BALANCE_ONLY=true — settlement uses wallet balance only (no live WeChat charge required).");
        }
        warnDevDemoSecrets();
    }

    private void rejectMockFlagsInStrictProfile() {
        if (securityProperties.mockEnabled()) {
            throw new IllegalStateException("Production/staging profile cannot run with aicabinet.security.mock-enabled=true");
        }
        if (lineWithdrawProperties.mockEnabled()) {
            throw new IllegalStateException(
                    "Production/staging profile cannot run with aicabinet.line-withdraw.mock-enabled=true (mock payouts)");
        }
        if (merchantWithdrawProperties.mockEnabled()) {
            throw new IllegalStateException(
                    "Production/staging profile cannot run with aicabinet.merchant-withdraw.mock-enabled=true (mock payouts)");
        }
    }

    private void validateStagingProfile() {
        log.warn("Staging mode active — WeChat Pay/MiniApp validation skipped; use prod profile before go-live");
        if (checkoutProperties.balanceOnly()) {
            log.info("Staging CHECKOUT_BALANCE_ONLY=true — suitable for no-merchant balance-only soak tests");
        }
        validateReconciliationConfig(false);
    }

    private void validateProductionProfile() {
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
        validatePayScoreConfig();
        validateProfitSharingConfig();
        validateReconciliationConfig(true);
    }

    private void validateReconciliationConfig(boolean requireWeChatWhenReal) {
        if (reconciliationProperties.mockEnabled()) {
            log.info("Reconciliation mock enabled (RECON_MOCK_ENABLED=true) — platform bills mirrored from ledger");
            return;
        }
        if (!weChatPayProperties.isConfigured()) {
            String msg = "RECON_MOCK_ENABLED=false requires WeChat Pay V3 configuration "
                    + "(or keep RECON_MOCK_ENABLED=true without credentials)";
            if (requireWeChatWhenReal) {
                throw new IllegalStateException(msg);
            }
            log.warn("Staging: {}", msg);
        }
    }

    private void validatePayScoreConfig() {
        if (!payScoreProperties.enabled()) {
            return;
        }
        if (!payScoreProperties.liveChargeEnabled()) {
            throw new IllegalStateException(
                    "PAYSCORE_ENABLED=true requires PAYSCORE_LIVE_CHARGE_ENABLED=true and a real charge implementation");
        }
        if (!payScoreProperties.hasChargeGateway()) {
            throw new IllegalStateException(
                    "PAYSCORE_LIVE_CHARGE_ENABLED=true requires PAYSCORE_CHARGE_GATEWAY_URL and PAYSCORE_CHARGE_GATEWAY_API_KEY");
        }
    }

    private void validateSmsMockCode() {
        String mockCode = authProperties.sms().mockCode();
        if (mockCode != null
                && ("123456".equals(mockCode) || "000000".equals(mockCode))) {
            throw new IllegalStateException(
                    "Production/staging cannot use the built-in mock SMS code (" + mockCode + "); "
                            + "set aicabinet.auth.sms.mock-code or disable the mock code path");
        }
    }

    private void validateCookieSecurity() {
        if (!authProperties.cookieEnabled()) {
            return;
        }
        if (isProdProfile() && !authProperties.cookieSecure()) {
            throw new IllegalStateException(
                    "Production requires AUTH_COOKIE_SECURE=true (admin session cookie must be Secure over HTTPS)");
        }
        if (isStagingProfile() && !isProdProfile() && authProperties.cookieSecure()) {
            log.warn("AUTH_COOKIE_SECURE=true on staging/localhost HTTP will make browsers drop the session cookie; "
                    + "set false unless the staging entrypoint is HTTPS");
        }
    }

    private void validateProfitSharingConfig() {
        if (!profitSharingProperties.enabled()) {
            log.info("WeChat profit sharing disabled (PROFIT_SHARING_ENABLED=false)");
            return;
        }
        if (profitSharingProperties.mockEnabled()) {
            throw new IllegalStateException(
                    "PROFIT_SHARING_MOCK_ENABLED=true is not allowed in production/staging");
        }
        if (!weChatPayProperties.isConfigured()) {
            throw new IllegalStateException(
                    "PROFIT_SHARING_ENABLED=true requires full WeChat Pay V3 configuration");
        }
        log.info("WeChat profit sharing enabled; retry={} batchSize={}",
                profitSharingProperties.retryEnabled(), profitSharingProperties.retryBatchSize());
    }

    private boolean isStrictProfile() {
        return isProdProfile() || isStagingProfile();
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStagingProfile() {
        if (stagingProperties.stagingMode()) {
            return true;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("staging".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private void warnIfDefaultMinioCredentials() {
        if ("minioadmin".equals(minioProperties.accessKey())
                || "minioadmin".equals(minioProperties.secretKey())) {
            log.warn("MinIO/OSS still using default minioadmin credentials — change before production go-live");
        }
    }

    private void warnIfLocalhostCors() {
        if (corsProperties.allowedOrigins() == null) {
            return;
        }
        boolean localhostOnly = corsProperties.allowedOrigins().stream()
                .allMatch(o -> o != null && (o.contains("localhost") || o.contains("127.0.0.1")));
        if (localhostOnly && isProdProfile()) {
            log.warn("CORS_ORIGIN still points to localhost — set real ops domain before go-live");
        }
    }

    /** Dev/demo：不阻断启动，但对默认可猜密钥打醒目 WARN，避免误当生产部署。 */
    private void warnDevDemoSecrets() {
        String internalKey = internalApiProperties.key();
        if (internalKey == null || internalKey.isBlank() || DEV_INTERNAL_KEY.equals(internalKey)) {
            log.warn("INTERNAL_API_KEY is unset or still the demo default ({}). "
                    + "Do not expose this stack to the public internet; use docker-compose.production.yml + strong key for go-live.",
                    DEV_INTERNAL_KEY);
        }
        String jwtSecret = authProperties.jwtSecret();
        if (jwtSecret == null || jwtSecret.isBlank() || DEV_JWT_SECRET.equals(jwtSecret)) {
            log.warn("JWT secret is unset or still the dev default. Replace JWT_SECRET before any production/staging deploy.");
        }
        String visionKey = visionApiProperties.key();
        if (visionKey == null || visionKey.isBlank() || DEV_VISION_KEY.equals(visionKey)) {
            log.warn("VISION_API_KEY is unset or still the dev default. Replace before production vision traffic.");
        }
        String mockCode = authProperties.sms().mockCode();
        if (mockCode != null && ("123456".equals(mockCode) || "000000".equals(mockCode))) {
            log.warn("SMS mock code is {} (dev-only). Strict prod/staging profiles reject this; disable mock SMS before go-live.",
                    mockCode);
        }
    }

    private static void requireSecret(String actual, String forbiddenDefault, String name) {
        if (actual == null || actual.isBlank() || forbiddenDefault.equals(actual)) {
            throw new IllegalStateException("Production/staging requires a strong " + name);
        }
        if (actual.length() < 32) {
            throw new IllegalStateException(name + " must be at least 32 characters");
        }
    }
}
