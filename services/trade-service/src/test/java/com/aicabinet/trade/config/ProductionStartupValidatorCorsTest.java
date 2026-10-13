package com.aicabinet.trade.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicabinet.common.security.InternalApiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 2026-10-13 生产首启实证钉住：application.yml 基座 CORS 列表硬编码 localhost:5173
 * 且 application-prod.yml 此前不覆盖 ⇒ prod profile 首启必被
 * {@code rejectLocalhostCorsInProd} 拒绝（staging 不做该检查，故长期未暴露）。
 * 修复：application-prod.yml 覆盖为 ${CORS_ORIGIN:} 纯环境注入。
 */
class ProductionStartupValidatorCorsTest {

    @Test
    void prodRejectsLocalhostCorsOrigin() {
        ProductionStartupValidator validator = CorsValidatorTestHarness.build("http://localhost:5173");
        assertThatThrownBy(validator::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGIN");
    }

    @Test
    void prodAcceptsRealDomainCorsOrigin() {
        ProductionStartupValidator validator = CorsValidatorTestHarness.build("https://kuangfuquan.com");
        validator.validateProductionConfig();
    }

    /**
     * 与 ProductionStartupValidatorTest#buildValidator 同构，但 CORS 来源可指定，
     * 专用于钉住 CORS 门禁行为；其余凭据一律给「合法生产值」。
     */
    private static final class CorsValidatorTestHarness {
        private static ProductionStartupValidator build(String corsOrigin) {
            org.springframework.core.env.Environment environment = mock(org.springframework.core.env.Environment.class);
            when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
            when(environment.getProperty("spring.datasource.password", "")).thenReturn("prod-db-password-not-default");
            when(environment.getProperty("PUBLIC_BASE_URL", "")).thenReturn("https://admin.example.com");

            SecurityProperties securityProperties = mock(SecurityProperties.class);
            when(securityProperties.mockEnabled()).thenReturn(false);

            StagingProperties stagingProperties = mock(StagingProperties.class);
            when(stagingProperties.stagingMode()).thenReturn(false);

            InternalApiProperties internalApiProperties = mock(InternalApiProperties.class);
            when(internalApiProperties.key()).thenReturn("prod-internal-key-32bytes-at-least!!");
            when(internalApiProperties.hasCidrRestriction()).thenReturn(true);

            AuthProperties.SmsProperties sms = new AuthProperties.SmsProperties(
                    "482913", 300, "https://sms.example.com/send",
                    "webhook", null, null, null, null, null);
            AuthProperties authProperties = mock(AuthProperties.class);
            when(authProperties.jwtSecret()).thenReturn("prod-jwt-secret-32bytes-at-least!!!!");
            when(authProperties.cookieEnabled()).thenReturn(true);
            when(authProperties.cookieSecure()).thenReturn(true);
            when(authProperties.sms()).thenReturn(sms);

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
            when(corsProperties.allowedOrigins()).thenReturn(List.of(corsOrigin));

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
    }
}
