package com.aicabinet.trade.config;

import com.aicabinet.common.security.InternalApiProperties;
import com.aicabinet.trade.config.AuthProperties.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AU-074：模板 CHANGE-ME 占位符（≠dev 默认且 ≥32 字符）曾能通过全部密钥门禁——
 * 忘改=公开已知密钥直接上生产。校验器必须按前缀黑名单拒掉示例模板自身的全部占位值。
 */
class ProductionStartupValidatorPlaceholderTest {

    /** infra/.env.production.example:10 的原样占位值（44 字符，旧门禁全绿）。 */
    private static final String TEMPLATE_JWT_PLACEHOLDER = "CHANGE-ME-production-jwt-secret-32bytes-min";
    /** infra/.env.production.example:11 的原样占位值。 */
    private static final String TEMPLATE_INTERNAL_PLACEHOLDER = "CHANGE-ME-production-internal-api-key-32b";

    private ProductionStartupValidator buildValidator(String jwtSecret, String internalKey) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] { "prod" });
        when(environment.getProperty("spring.datasource.password", "")).thenReturn("prod-db-password-not-default");
        when(environment.getProperty("PUBLIC_BASE_URL", "")).thenReturn("https://admin.example.com");

        SecurityProperties securityProperties = mock(SecurityProperties.class);
        when(securityProperties.mockEnabled()).thenReturn(false);
        StagingProperties stagingProperties = mock(StagingProperties.class);
        when(stagingProperties.stagingMode()).thenReturn(false);

        InternalApiProperties internalApiProperties = mock(InternalApiProperties.class);
        when(internalApiProperties.key()).thenReturn(internalKey);
        when(internalApiProperties.hasCidrRestriction()).thenReturn(true);

        AuthProperties authProperties = mock(AuthProperties.class);
        when(authProperties.jwtSecret()).thenReturn(jwtSecret);
        when(authProperties.cookieEnabled()).thenReturn(true);
        when(authProperties.cookieSecure()).thenReturn(true);
        when(authProperties.sms()).thenReturn(new SmsProperties(
                "482913", 300, "https://sms.example.com/send",
                "webhook", null, null, null, null, null));

        WeChatPayProperties weChatPayProperties = mock(WeChatPayProperties.class);
        when(weChatPayProperties.enabled()).thenReturn(false);
        when(weChatPayProperties.isConfigured()).thenReturn(true);
        when(weChatPayProperties.platformCertAutoFetch()).thenReturn(true);
        when(weChatPayProperties.platformCert()).thenReturn(null);
        WeChatMiniAppProperties weChatMiniAppProperties = mock(WeChatMiniAppProperties.class);
        when(weChatMiniAppProperties.enabled()).thenReturn(false);
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
        when(checkoutProperties.balanceOnly()).thenReturn(true);
        LineWithdrawProperties lineWithdrawProperties = mock(LineWithdrawProperties.class);
        when(lineWithdrawProperties.mockEnabled()).thenReturn(false);
        MerchantWithdrawProperties merchantWithdrawProperties = mock(MerchantWithdrawProperties.class);
        when(merchantWithdrawProperties.mockEnabled()).thenReturn(false);
        IdentityVerifyProperties identityVerifyProperties = mock(IdentityVerifyProperties.class);
        when(identityVerifyProperties.isConfigured()).thenReturn(true);

        return new ProductionStartupValidator(
                environment, securityProperties, stagingProperties, internalApiProperties, authProperties,
                weChatPayProperties, weChatMiniAppProperties, visionApiProperties, minioProperties, corsProperties,
                profitSharingProperties, payScoreProperties, reconciliationProperties, checkoutProperties,
                lineWithdrawProperties, merchantWithdrawProperties, identityVerifyProperties);
    }

    @Test
    void prodRejectsTemplateJwtPlaceholder() {
        assertThatThrownBy(() -> buildValidator(TEMPLATE_JWT_PLACEHOLDER,
                "prod-internal-key-32bytes-at-least!!").validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void prodRejectsTemplateInternalKeyPlaceholder() {
        assertThatThrownBy(() -> buildValidator("prod-jwt-secret-32bytes-at-least!!!!",
                TEMPLATE_INTERNAL_PLACEHOLDER).validateProductionConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_KEY");
    }

    @Test
    void prodRejectsYourPrefixAndChangemeVariantPlaceholders() {
        assertThatThrownBy(() -> buildValidator("YOUR_OSS_ACCESS_KEY_ID_0123456789abcdef",
                "prod-internal-key-32bytes-at-least!!").validateProductionConfig())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> buildValidator("prod-jwt-secret-32bytes-at-least!!!!",
                "changeme-internal-key-0123456789abcdef!!").validateProductionConfig())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realSecret_containingChangeAsMidWord_stillPasses() {
        // 黑名单只认前缀形态：真实随机串恰含 "change" 不误杀
        String real = "b7change9f2e-me0d4a8c6f1e3b5d7a9c02468";
        assertThatCode(() -> buildValidator(real, real).validateProductionConfig())
                .doesNotThrowAnyException();
    }
}
