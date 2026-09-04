package com.aicabinet.trade.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.trade.config.AuthProperties.SmsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class ProductionStartupValidatorTest {

    @Test
    void prodRejectsBuiltInMockSmsCode() {
        ProductionStartupValidator validator = buildValidator("prod", "123456", true, true);
        assertThatThrownBy(validator::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock SMS code");
    }

    @Test
    void prodRequiresSecureCookie() {
        ProductionStartupValidator validator = buildValidator("prod", "482913", true, false);
        assertThatThrownBy(validator::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_COOKIE_SECURE");
    }

    @Test
    void prodRequiresCookieEnabled() {
        ProductionStartupValidator validator = buildValidator("prod", "482913", false, true);
        assertThatThrownBy(validator::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_COOKIE_ENABLED");
    }

    @Test
    void stagingRequiresCookieEnabled() {
        ProductionStartupValidator validator = buildValidator("staging", "482913", false, true);
        assertThatThrownBy(validator::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_COOKIE_ENABLED");
    }

    @Test
    void prodAcceptsRealSmsCodeWithSecureCookie() {
        ProductionStartupValidator validator = buildValidator("prod", "482913", true, true);
        validator.validateProductionConfig();
    }

    private ProductionStartupValidator buildValidator(
            String profile, String smsCode, boolean cookieEnabled, boolean cookieSecure) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {profile});

        SecurityProperties securityProperties = mock(SecurityProperties.class);
        when(securityProperties.mockEnabled()).thenReturn(false);

        StagingProperties stagingProperties = mock(StagingProperties.class);
        when(stagingProperties.stagingMode()).thenReturn("staging".equals(profile));

        InternalApiProperties internalApiProperties = mock(InternalApiProperties.class);
        when(internalApiProperties.key()).thenReturn("prod-internal-key-32bytes-at-least!!");
        when(internalApiProperties.hasCidrRestriction()).thenReturn(true);

        AuthProperties authProperties = mock(AuthProperties.class);
        when(authProperties.jwtSecret()).thenReturn("prod-jwt-secret-32bytes-at-least!!!!");
        when(authProperties.cookieEnabled()).thenReturn(cookieEnabled);
        when(authProperties.cookieSecure()).thenReturn(cookieSecure);
        when(authProperties.sms()).thenReturn(new SmsProperties(
                smsCode, 300, "https://sms.example.com/send",
                "webhook", null, null, null, null, null));

        WeChatPayProperties weChatPayProperties = mock(WeChatPayProperties.class);
        when(weChatPayProperties.isConfigured()).thenReturn(true);
        when(weChatPayProperties.platformCertAutoFetch()).thenReturn(true);
        when(weChatPayProperties.platformCert()).thenReturn(null);

        WeChatMiniAppProperties weChatMiniAppProperties = mock(WeChatMiniAppProperties.class);
        when(weChatMiniAppProperties.isConfigured()).thenReturn(true);

        VisionApiProperties visionApiProperties = mock(VisionApiProperties.class);
        when(visionApiProperties.key()).thenReturn("prod-vision-key-32bytes-at-least!!");

        MinioProperties minioProperties = mock(MinioProperties.class);
        when(minioProperties.accessKey()).thenReturn("prod-minio-user");
        when(minioProperties.secretKey()).thenReturn("prod-minio-secret-32bytes-at-least");

        CorsProperties corsProperties = mock(CorsProperties.class);
        when(corsProperties.allowedOrigins()).thenReturn(List.of("https://ops.example.com"));

        ProfitSharingProperties profitSharingProperties = mock(ProfitSharingProperties.class);
        when(profitSharingProperties.enabled()).thenReturn(false);

        PayScoreProperties payScoreProperties = mock(PayScoreProperties.class);
        when(payScoreProperties.enabled()).thenReturn(false);

        ReconciliationProperties reconciliationProperties = mock(ReconciliationProperties.class);
        when(reconciliationProperties.mockEnabled()).thenReturn(false);

        CheckoutProperties checkoutProperties = mock(CheckoutProperties.class);
        when(checkoutProperties.balanceOnly()).thenReturn(false);

        LineWithdrawProperties lineWithdrawProperties = mock(LineWithdrawProperties.class);
        when(lineWithdrawProperties.mockEnabled()).thenReturn(false);

        MerchantWithdrawProperties merchantWithdrawProperties = mock(MerchantWithdrawProperties.class);
        when(merchantWithdrawProperties.mockEnabled()).thenReturn(false);

        IdentityVerifyProperties identityVerifyProperties = mock(IdentityVerifyProperties.class);
        when(identityVerifyProperties.isConfigured()).thenReturn(true);

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

    @Test
    void prodRejectsReconciliationMock() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

        SecurityProperties securityProperties = mock(SecurityProperties.class);
        when(securityProperties.mockEnabled()).thenReturn(false);

        StagingProperties stagingProperties = mock(StagingProperties.class);
        when(stagingProperties.stagingMode()).thenReturn(false);

        InternalApiProperties internalApiProperties = mock(InternalApiProperties.class);
        when(internalApiProperties.key()).thenReturn("prod-internal-key-32bytes-at-least!!");

        AuthProperties authProperties = mock(AuthProperties.class);
        when(authProperties.jwtSecret()).thenReturn("prod-jwt-secret-32bytes-at-least!!!!");
        when(authProperties.cookieEnabled()).thenReturn(true);
        when(authProperties.cookieSecure()).thenReturn(true);
        when(authProperties.sms()).thenReturn(new SmsProperties(
                "482913", 300, "https://sms.example.com/send",
                "webhook", null, null, null, null, null));

        ReconciliationProperties reconciliationProperties = mock(ReconciliationProperties.class);
        when(reconciliationProperties.mockEnabled()).thenReturn(true);

        LineWithdrawProperties lineWithdrawProperties = mock(LineWithdrawProperties.class);
        when(lineWithdrawProperties.mockEnabled()).thenReturn(false);
        MerchantWithdrawProperties merchantWithdrawProperties = mock(MerchantWithdrawProperties.class);
        when(merchantWithdrawProperties.mockEnabled()).thenReturn(false);

        ProductionStartupValidator validator = new ProductionStartupValidator(
                environment,
                securityProperties,
                stagingProperties,
                internalApiProperties,
                authProperties,
                mock(WeChatPayProperties.class),
                mock(WeChatMiniAppProperties.class),
                mock(VisionApiProperties.class),
                mock(MinioProperties.class),
                mock(CorsProperties.class),
                mock(ProfitSharingProperties.class),
                mock(PayScoreProperties.class),
                reconciliationProperties,
                mock(CheckoutProperties.class),
                lineWithdrawProperties,
                merchantWithdrawProperties,
                mock(IdentityVerifyProperties.class));

        assertThatThrownBy(validator::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECON_MOCK_ENABLED");
    }
}
