package com.aicabinet.jiangyi.config;

import com.aicabinet.common.security.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 将邑 gateway 的 prod/staging 启动校验（AU-075）。
 *
 * <p>此前 gateway 完全没有启动校验：生产叠加层不设 profile/不注入 JIANGYI_*，
 * {@code /internal/**} 可以拿 dev 默认 key + 空 CIDR（不限来源 IP）静默上线；
 * {@code JIANGYI_JWT_SECRET} 只有运行期 503 fail-closed，配置错误要到柜机激活当天才暴露。
 * 本校验器把这些全部前移到启动期 fail-loud。</p>
 */
@Component
public class ProductionStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionStartupValidator.class);
    private static final String DEV_INTERNAL_KEY = "dev-internal-key-change-me";
    /** 与 trade ProductionStartupValidator / check-env.ps1 同口径（门禁 check-env-placeholders 三端钉一致）。 */
    private static final String[] PLACEHOLDER_PREFIXES = {
            "change-me", "change_me", "changeme", "changeto", "change-to",
            "replace-me", "replace_me", "placeholder", "your-", "your_", "wxyour", "xxx"
    };
    private static final int MIN_SECRET_LENGTH = 32;

    private final Environment environment;
    private final InternalApiProperties internalApiProperties;
    private final String jwtSecret;

    public ProductionStartupValidator(Environment environment,
                                      InternalApiProperties internalApiProperties,
                                      @Value("${aicabinet.jiangyi.jwt-secret:}") String jwtSecret) {
        this.environment = environment;
        this.internalApiProperties = internalApiProperties;
        this.jwtSecret = jwtSecret;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!isStrictProfile()) {
            warnNonStrict();
            return;
        }
        requireInternalKey();
        if (!internalApiProperties.hasCidrRestriction()) {
            throw new IllegalStateException(
                    "Production/staging requires INTERNAL_API_ALLOWED_CIDRS for jiangyi-gateway "
                            + "(empty CIDR means any source IP with a valid key can call /internal/**)");
        }
        requireJwtSecret();
        log.info("jiangyi-gateway production configuration validated");
    }

    private void requireInternalKey() {
        String key = internalApiProperties.key();
        if (key == null || key.isBlank() || DEV_INTERNAL_KEY.equals(key)) {
            throw new IllegalStateException("Production/staging requires a strong INTERNAL_API_KEY");
        }
        if (key.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("INTERNAL_API_KEY must be at least 32 characters");
        }
        rejectPlaceholder(key, "INTERNAL_API_KEY");
    }

    private void requireJwtSecret() {
        // 运行期 503 fail-closed 保留为兜底，但严格 profile 下「没配/占位」必须启动期就暴露——
        // 柜机激活当天才发现 token 签发不可用是最差的发现路径。
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "Production/staging requires JIANGYI_JWT_SECRET (device token signing; "
                            + "without it /jiangyi/api/token is 503 and no cabinet can activate)");
        }
        if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("JIANGYI_JWT_SECRET must be at least 32 characters");
        }
        rejectPlaceholder(jwtSecret, "JIANGYI_JWT_SECRET");
    }

    private static void rejectPlaceholder(String value, String name) {
        String lowered = value.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : PLACEHOLDER_PREFIXES) {
            if (lowered.startsWith(prefix)) {
                throw new IllegalStateException(name + " still uses the example-template placeholder (\""
                        + prefix + "*\"); replace it with a generated secret before go-live");
            }
        }
    }

    private boolean isStrictProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "staging".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private void warnNonStrict() {
        if (internalApiProperties.key() == null || internalApiProperties.key().isBlank()
                || DEV_INTERNAL_KEY.equals(internalApiProperties.key())) {
            log.warn("INTERNAL_API_KEY 为空或仍是 dev 默认——仅限本机联调；任何可公网达到的部署必须换强密钥");
        }
    }
}
