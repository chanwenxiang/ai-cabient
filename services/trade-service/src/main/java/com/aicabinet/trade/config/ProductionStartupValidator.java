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
    /** application.yml / docker-compose 本地默认库密码，prod/staging 必须覆盖。 */
    private static final String DEV_DATASOURCE_PASSWORD = "aicabinet";

    // AU-074：示例模板的占位值（≠dev 默认且长度达标）曾能过全部密钥门禁——忘改=公开已知密钥上生产。
    // 只按前缀判定（小写），不误杀恰含 "change"/"your" 子串的真实随机串。
    private static final String[] PLACEHOLDER_PREFIXES = {
            "change-me", "change_me", "changeme", "changeto", "change-to",
            "replace-me", "replace_me", "placeholder", "your-", "your_", "wxyour", "xxx"
    };

    private final Environment environment;
    private final SecurityProperties securityProperties;
    private final StagingProperties stagingProperties;
    private final InternalApiProperties internalApiProperties;
    private final AuthProperties authProperties;
    private final WeChatPayProperties weChatPayProperties;
    private final WeChatMiniAppProperties weChatMiniAppProperties;
    private final MinioProperties minioProperties;
    private final CorsProperties corsProperties;
    private final ProfitSharingProperties profitSharingProperties;
    private final PayScoreProperties payScoreProperties;
    private final ReconciliationProperties reconciliationProperties;
    private final CheckoutProperties checkoutProperties;
    private final LineWithdrawProperties lineWithdrawProperties;
    private final MerchantWithdrawProperties merchantWithdrawProperties;
    private final IdentityVerifyProperties identityVerifyProperties;

    public ProductionStartupValidator(Environment environment,
                                      SecurityProperties securityProperties,
                                      StagingProperties stagingProperties,
                                      InternalApiProperties internalApiProperties,
                                      AuthProperties authProperties,
                                      WeChatPayProperties weChatPayProperties,
                                      WeChatMiniAppProperties weChatMiniAppProperties,
                                      MinioProperties minioProperties,
                                      CorsProperties corsProperties,
                                      ProfitSharingProperties profitSharingProperties,
                                      PayScoreProperties payScoreProperties,
                                      ReconciliationProperties reconciliationProperties,
                                      CheckoutProperties checkoutProperties,
                                      LineWithdrawProperties lineWithdrawProperties,
                                      MerchantWithdrawProperties merchantWithdrawProperties,
                                      IdentityVerifyProperties identityVerifyProperties) {
        this.environment = environment;
        this.securityProperties = securityProperties;
        this.stagingProperties = stagingProperties;
        this.internalApiProperties = internalApiProperties;
        this.authProperties = authProperties;
        this.weChatPayProperties = weChatPayProperties;
        this.weChatMiniAppProperties = weChatMiniAppProperties;
        this.minioProperties = minioProperties;
        this.corsProperties = corsProperties;
        this.profitSharingProperties = profitSharingProperties;
        this.payScoreProperties = payScoreProperties;
        this.reconciliationProperties = reconciliationProperties;
        this.checkoutProperties = checkoutProperties;
        this.lineWithdrawProperties = lineWithdrawProperties;
        this.merchantWithdrawProperties = merchantWithdrawProperties;
        this.identityVerifyProperties = identityVerifyProperties;
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
        // VISION_API_KEY 强密钥检查已随旧云识别链退役删除（2026-10-11 批5/收尾）——消费方 VisionServiceClient 已不存在
        rejectDefaultDatasourcePassword();
        // AU-177（批次 10，纵深加固）：演示号段清除依赖 seed_env='none'（V344）。正常由
        // application-prod.yml 硬编码保证；此处校验防有人改动 profile 文件或以 env 覆盖
        // 把 seed_env 拉回 local/demo 而演示超管（13900000001/123456）带全家当上生产。
        // 键序：yml 嵌套键 aicabinet.seed_env（application-prod.yml 硬编码 none）→ SEED_ENV env 覆盖 → 缺省按 none
        String seedEnv = environment.getProperty("aicabinet.seed_env");
        if (seedEnv == null) {
            seedEnv = environment.getProperty("SEED_ENV");
        }
        if (seedEnv == null) {
            seedEnv = "none";
        }
        if (!"none".equalsIgnoreCase(seedEnv)) {
            throw new IllegalStateException(
                    "Production/staging requires SEED_ENV=none (got '" + seedEnv + "'): demo accounts would remain active");
        }
        // 2026-10-20：短信通道双轨化——webhook 网关或阿里云四件套（SMS_PROVIDER=aliyun/auto）
        // 任一配好即放行；两者皆缺仍 fail-closed。原 webhook-only 口径会把「已配齐阿里云、
        // 故意不再要求 webhook」的合法生产配置拦成启动失败（2026-10-20 生产实证）。
        if (!authProperties.sms().hasWebhook() && !authProperties.sms().hasAliyun()) {
            throw new IllegalStateException(
                    "Production/staging requires an SMS channel: set SMS_WEBHOOK_URL "
                            + "(aicabinet.auth.sms.webhook-url) or SMS_ALIYUN_* with SMS_PROVIDER=aliyun/auto");
        }
        validateSmsMockCode();
        validateCookieSecurity();
        rejectDefaultMinioCredentials();
        rejectLocalhostCorsInProd();
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
        // 生产禁止对账 mock（staging 仍允许 soak）；避免假账单混入正式对账
        if (isProdProfile() && reconciliationProperties.mockEnabled()) {
            throw new IllegalStateException(
                    "Production profile cannot run with RECON_MOCK_ENABLED=true / aicabinet.reconciliation.mock-enabled=true");
        }
    }

    private void validateStagingProfile() {
        log.warn("Staging mode active — WeChat Pay/MiniApp validation skipped; use prod profile before go-live");
        if (checkoutProperties.balanceOnly()) {
            log.info("Staging CHECKOUT_BALANCE_ONLY=true — suitable for no-merchant balance-only soak tests");
        }
        // soak 环境同样禁止空 CIDR：内网接口不能仅靠可猜 key
        requireInternalApiCidr("Staging");
        validateReconciliationConfig(false);
    }

    private void validateProductionProfile() {
        // 2026-10-13 生产首启调整：「暂不上支付」是合法的产品阶段（CHECKOUT_BALANCE_ONLY 余额-only
        // 结算本就是一等公民模式）。改为按 enabled 分档：显式关闭（WECHAT_PAY_ENABLED=false）时
        // 降级为醒目 WARN 放行；一旦 enabled=true 且凭据不全，仍硬拒绝——fail-loud 语义不变，
        // 不存在「忘了配也悄悄起」的路径。
        if (weChatPayProperties.enabled() && !weChatPayProperties.isConfigured()) {
            throw new IllegalStateException("Production requires WeChat Pay V3 configuration");
        }
        if (!weChatPayProperties.enabled()) {
            log.warn("WECHAT_PAY_ENABLED=false —— 支付功能显式关闭（暂不上支付阶段），"
                    + "结算走余额-only；上线支付前必须补齐微信支付 V3 全套凭据并置 true，届时恢复硬校验");
        }
        boolean hasStaticCert = weChatPayProperties.platformCert() != null
                && !weChatPayProperties.platformCert().isBlank();
        if (weChatPayProperties.enabled() && !hasStaticCert && !weChatPayProperties.platformCertAutoFetch()) {
            throw new IllegalStateException(
                    "Production requires WECHAT_PLATFORM_CERT or WECHAT_PLATFORM_CERT_AUTO_FETCH=true");
        }
        if (weChatMiniAppProperties.enabled() && !weChatMiniAppProperties.isConfigured()) {
            throw new IllegalStateException("Production requires WeChat MiniApp configuration");
        }
        if (!weChatMiniAppProperties.enabled()) {
            log.warn("WECHAT_MINIAPP_ENABLED=false —— 小程序微信登录/订阅消息显式关闭；"
                    + "消费者登录走手机号+短信验证码通道");
        }
        validatePayScoreConfig();
        validateProfitSharingConfig();
        // 支付显式关闭时无通道账单可对（对账门禁随支付开关同档降级；启用支付后恢复硬校验）
        validateReconciliationConfig(weChatPayProperties.enabled());
        if (!identityVerifyProperties.isConfigured()) {
            // 实名二要素唯一消费方是「免密支付」开通链路（IdentityVerifyClient 对未配置本就 fail-closed），
            // 支付显式关闭的阶段允许暂缓；启用支付后此门禁恢复硬拒绝。
            if (weChatPayProperties.enabled()) {
                throw new IllegalStateException(
                        "Production requires IDENTITY_VERIFY_BASE_URL / aicabinet.identity-verify.base-url "
                                + "(or keep AICABINET_MOCK_ENABLED=true only in non-prod)");
            }
            log.warn("IDENTITY_VERIFY_BASE_URL 未配置 —— 实名二要素不可用（支付显式关闭阶段可暂缓；"
                    + "开通支付/免密支付前必须配置，运行期 fail-closed 不变）");
        }
        requireInternalApiCidr("Production");
    }

    private void requireInternalApiCidr(String envLabel) {
        if (!internalApiProperties.hasCidrRestriction()) {
            throw new IllegalStateException(
                    envLabel + " requires INTERNAL_API_ALLOWED_CIDRS (private Docker/VPC CIDRs); "
                            + "empty CIDR means any source IP with a valid key can call /internal/**");
        }
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
        if (isProdProfile() && !authProperties.cookieEnabled()) {
            throw new IllegalStateException(
                    "Production requires AUTH_COOKIE_ENABLED=true (admin must use HttpOnly session cookie; "
                            + "Bearer token in localStorage is not allowed)");
        }
        if (isStagingProfile() && !isProdProfile() && !authProperties.cookieEnabled()) {
            throw new IllegalStateException(
                    "Staging requires AUTH_COOKIE_ENABLED=true (same admin Cookie session policy as production)");
        }
        if (!authProperties.cookieEnabled()) {
            return;
        }
        if (isProdProfile() && authProperties.cookieEnabled()) {
            // 2026-10-13 生产首启实证：原版无条件硬要求 Secure=true，导致 HTTPS 就绪前的 HTTP 过渡期
            // 浏览器拒存 Secure cookie ⇒ 登录成功但后台全 401（静默失败）。改为按 PUBLIC_BASE_URL
            // 协议声明阶段：声明 https ⇒ 必须 Secure；声明 http/未声明 ⇒ 必须 false（错配即硬拒，
            // http+Secure=true 正是「浏览器拒存⇒静默 401」的错误形态）。HTTPS 上线时两处一起改回。
            String publicBaseUrl = environment.getProperty("PUBLIC_BASE_URL", "");
            boolean httpsStage = publicBaseUrl.startsWith("https://");
            if (httpsStage && !authProperties.cookieSecure()) {
                throw new IllegalStateException(
                        "PUBLIC_BASE_URL is https:// but AUTH_COOKIE_SECURE=false: admin session cookie must be "
                                + "Secure over HTTPS; set AUTH_COOKIE_SECURE=true");
            }
            if (!httpsStage && authProperties.cookieSecure()) {
                throw new IllegalStateException(
                        "AUTH_COOKIE_SECURE=true with non-HTTPS PUBLIC_BASE_URL ("
                                + (publicBaseUrl.isEmpty() ? "unset" : publicBaseUrl)
                                + "): browsers will silently drop the session cookie and every admin API returns "
                                + "401; set AUTH_COOKIE_SECURE=false until HTTPS is live (or PUBLIC_BASE_URL to https://)");
            }
            if (!httpsStage) {
                log.warn("PROD pre-HTTPS stage (PUBLIC_BASE_URL={}): AUTH_COOKIE_SECURE=false is expected now; "
                        + "flip PUBLIC_BASE_URL to https:// and AUTH_COOKIE_SECURE=true together after HTTPS is live",
                        publicBaseUrl.isEmpty() ? "(unset)" : publicBaseUrl);
            }
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

    private void rejectDefaultDatasourcePassword() {
        String password = environment.getProperty("spring.datasource.password", "");
        if (password == null || password.isBlank() || DEV_DATASOURCE_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "Production/staging cannot use default DB password (aicabinet); "
                            + "set SPRING_DATASOURCE_PASSWORD / POSTGRES_PASSWORD");
        }
    }

    private void rejectDefaultMinioCredentials() {
        if ("minioadmin".equals(minioProperties.accessKey())
                || "minioadmin".equals(minioProperties.secretKey())) {
            throw new IllegalStateException(
                    "Production/staging cannot use default MinIO credentials (minioadmin); "
                            + "set MINIO_ACCESS_KEY / MINIO_SECRET_KEY");
        }
    }

    private void rejectLocalhostCorsInProd() {
        if (!isProdProfile() || corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isEmpty()) {
            return;
        }
        boolean hasLocalhost = corsProperties.allowedOrigins().stream()
                .anyMatch(o -> o != null && (o.contains("localhost") || o.contains("127.0.0.1")));
        if (hasLocalhost) {
            throw new IllegalStateException(
                    "Production CORS_ORIGIN must not include localhost/127.0.0.1; set real ops domain(s)");
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
        String mockCode = authProperties.sms().mockCode();
        if (mockCode != null && ("123456".equals(mockCode) || "000000".equals(mockCode))) {
            log.warn("SMS mock code is {} (dev-only). Strict prod/staging profiles reject this; disable mock SMS before go-live.",
                    mockCode);
        }
        String dbPassword = environment.getProperty("spring.datasource.password", "");
        if (dbPassword == null || dbPassword.isBlank() || DEV_DATASOURCE_PASSWORD.equals(dbPassword)) {
            log.warn("SPRING_DATASOURCE_PASSWORD is unset or still the default '{}'. "
                            + "Strict prod/staging profiles reject this; set a strong password before go-live.",
                    DEV_DATASOURCE_PASSWORD);
        }
    }

    private static void requireSecret(String actual, String forbiddenDefault, String name) {
        if (actual == null || actual.isBlank() || forbiddenDefault.equals(actual)) {
            throw new IllegalStateException("Production/staging requires a strong " + name);
        }
        if (actual.length() < 32) {
            throw new IllegalStateException(name + " must be at least 32 characters");
        }
        String lowered = actual.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : PLACEHOLDER_PREFIXES) {
            if (lowered.startsWith(prefix)) {
                throw new IllegalStateException(name + " still uses the example-template placeholder (\""
                        + prefix + "*\"); replace it with a generated secret before go-live");
            }
        }
    }
}
