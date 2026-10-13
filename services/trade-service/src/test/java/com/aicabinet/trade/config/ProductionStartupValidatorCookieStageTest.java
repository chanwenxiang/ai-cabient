package com.aicabinet.trade.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.trade.config.AuthProperties.SmsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * 2026-10-13 生产首启实证钉住测试（cookie 阶段模式）：
 * 原版无条件硬要求 AUTH_COOKIE_SECURE=true，导致 HTTPS 就绪前的 HTTP 过渡期浏览器拒存
 * Secure cookie ⇒ 登录成功但后台全 401（静默失败）。改为按 PUBLIC_BASE_URL 协议声明阶段：
 * <ul>
 *   <li>https:// + SECURE=false ⇒ 硬拒绝（声明 HTTPS 却不加密会话）；</li>
 *   <li>http:///unset + SECURE=true ⇒ 硬拒绝（浏览器拒存 ⇒ 静默 401，本次实证形态）；</li>
 *   <li>http:///unset + SECURE=false ⇒ 放行（WARN，过渡阶段）；</li>
 *   <li>https:// + SECURE=true ⇒ 放行（HTTPS 就绪终态）。</li>
 * </ul>
 */
class ProductionStartupValidatorCookieStageTest {

    @Test
    void httpsStageRequiresSecureCookie() {
        ProductionStartupValidator v = build("https://kuangfuquan.com", false);
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_COOKIE_SECURE=false");
    }

    @Test
    void httpStageWithSecureCookieIsTheSilent401ComboAndThrows() {
        ProductionStartupValidator v = build("http://120.25.246.179", true);
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silently drop the session cookie");
    }

    @Test
    void unsetPublicBaseUrlCountsAsHttpStage() {
        ProductionStartupValidator v = build("", true);
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silently drop the session cookie");
    }

    @Test
    void httpStageWithNonSecureCookiePasses() {
        ProductionStartupValidator v = build("http://120.25.246.179", false);
        assertThatCode(v::validateProductionConfig).doesNotThrowAnyException();
    }

    @Test
    void httpsStageWithSecureCookiePasses() {
        ProductionStartupValidator v = build("https://kuangfuquan.com", true);
        assertThatCode(v::validateProductionConfig).doesNotThrowAnyException();
    }

    /** 其余凭据全部为合法生产值，仅 PUBLIC_BASE_URL 与 cookieSecure 按参数变化。 */
    private static ProductionStartupValidator build(String publicBaseUrl, boolean cookieSecure) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(environment.getProperty("spring.datasource.password", "")).thenReturn("prod-db-password-not-default");
        when(environment.getProperty("PUBLIC_BASE_URL", "")).thenReturn(publicBaseUrl);

        SecurityProperties securityProperties = mock(SecurityProperties.class);
        when(securityProperties.mockEnabled()).thenReturn(false);

        StagingProperties stagingProperties = mock(StagingProperties.class);
        when(stagingProperties.stagingMode()).thenReturn(false);

        InternalApiProperties internalApiProperties = mock(InternalApiProperties.class);
        when(internalApiProperties.key()).thenReturn("prod-internal-key-32bytes-at-least!!");
        when(internalApiProperties.hasCidrRestriction()).thenReturn(true);

        AuthProperties authProperties = mock(AuthProperties.class);
        when(authProperties.jwtSecret()).thenReturn("prod-jwt-secret-32bytes-at-least!!!!");
        when(authProperties.cookieEnabled()).thenReturn(true);
        when(authProperties.cookieSecure()).thenReturn(cookieSecure);
        when(authProperties.sms()).thenReturn(new SmsProperties(
                "482913", 300, "https://sms.example.com/send",
                "webhook", null, null, null, null, null));

        WeChatPayProperties weChatPayProperties = mock(WeChatPayProperties.class);
        when(weChatPayProperties.enabled()).thenReturn(false);
        when(weChatPayProperties.isConfigured()).thenReturn(false);
        when(weChatPayProperties.platformCert()).thenReturn("");
        when(weChatPayProperties.platformCertAutoFetch()).thenReturn(true);

        WeChatMiniAppProperties weChatMiniAppProperties = mock(WeChatMiniAppProperties.class);
        when(weChatMiniAppProperties.enabled()).thenReturn(false);
        when(weChatMiniAppProperties.isConfigured()).thenReturn(false);

        VisionApiProperties visionApiProperties = mock(VisionApiProperties.class);
        when(visionApiProperties.key()).thenReturn("prod-vision-key-32bytes-at-least!!");

        MinioProperties minioProperties = mock(MinioProperties.class);
        when(minioProperties.accessKey()).thenReturn("prod-minio-user");
        when(minioProperties.secretKey()).thenReturn("prod-minio-secret-32bytes-at-least");

        CorsProperties corsProperties = mock(CorsProperties.class);
        when(corsProperties.allowedOrigins()).thenReturn(List.of("https://kuangfuquan.com"));

        ProfitSharingProperties profitSharingProperties = mock(ProfitSharingProperties.class);
        when(profitSharingProperties.enabled()).thenReturn(false);

        PayScoreProperties payScoreProperties = mock(PayScoreProperties.class);
        when(payScoreProperties.enabled()).thenReturn(false);

        ReconciliationProperties reconciliationProperties = mock(ReconciliationProperties.class);
        when(reconciliationProperties.mockEnabled()).thenReturn(false);

        CheckoutProperties checkoutProperties = mock(CheckoutProperties.class);
        when(checkoutProperties.balanceOnly()).thenReturn(true);

        LineWithdrawProperties lineWithdrawProperties = mock(LineWithdrawProperties.class);
        when(lineWithdrawProperties.mockEnabled()).thenReturn(false);

        MerchantWithdrawProperties merchantWithdrawProperties = mock(MerchantWithdrawProperties.class);
        when(merchantWithdrawProperties.mockEnabled()).thenReturn(false);

        IdentityVerifyProperties identityVerifyProperties = mock(IdentityVerifyProperties.class);
        when(identityVerifyProperties.isConfigured()).thenReturn(false);

        return new ProductionStartupValidator(
                environment,
                securityProperties,
                stagingProperties,
                internalApiProperties,
                authProperties,
                weChatPayProperties,
                weChatMiniAppProperties,
                visionApiProperties,
                minioProperties,
                corsProperties,
                profitSharingProperties,
                payScoreProperties,
                reconciliationProperties,
                checkoutProperties,
                lineWithdrawProperties,
                merchantWithdrawProperties,
                identityVerifyProperties);
    }
}
