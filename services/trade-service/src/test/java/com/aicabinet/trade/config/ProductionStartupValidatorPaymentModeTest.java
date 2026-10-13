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
 * 2026-10-13 生产首启调整（用户决策：暂不上微信/支付宝，余额-only 阶段）钉住测试：
 * <ul>
 *   <li>WECHAT_PAY_ENABLED=true 且凭据不全 ⇒ 仍硬拒绝（fail-loud 不变，负向验证）；</li>
 *   <li>WECHAT_PAY_ENABLED=false 凭据空 ⇒ 放行（WARN），身份核验/小程序同档降级；</li>
 *   <li>WECHAT_MINIAPP_ENABLED=true 且凭据不全 ⇒ 硬拒绝。</li>
 * </ul>
 */
class ProductionStartupValidatorPaymentModeTest {

    @Test
    void prodPayEnabledButUnconfiguredStillThrows() {
        ProductionStartupValidator v = build(true, false, false, false, false);
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WeChat Pay V3");
    }

    @Test
    void prodPayExplicitlyDisabledWithNoCredentialsPasses() {
        ProductionStartupValidator v = build(false, false, false, false, false);
        assertThatCode(v::validateProductionConfig).doesNotThrowAnyException();
    }

    @Test
    void prodMiniAppEnabledButUnconfiguredStillThrows() {
        ProductionStartupValidator v = build(true, true, true, false, false);
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MiniApp");
    }

    @Test
    void prodPayEnabledRequiresIdentityVerifyUpstream() {
        ProductionStartupValidator v = build(true, true, false, false, false);
        assertThatThrownBy(v::validateProductionConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_VERIFY_BASE_URL");
    }

    @Test
    void prodPayDisabledToleratesMissingIdentityVerify() {
        ProductionStartupValidator v = build(false, false, false, false, false);
        assertThatCode(v::validateProductionConfig).doesNotThrowAnyException();
    }

    /** 全部其余凭据均为「合法生产值」，只让微信三件套/身份核验按参数变化。 */
    private static ProductionStartupValidator build(
            boolean payEnabled, boolean payConfigured,
            boolean miniappEnabled, boolean miniappConfigured,
            boolean identityConfigured) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(environment.getProperty("spring.datasource.password", "")).thenReturn("prod-db-password-not-default");

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
        when(authProperties.cookieSecure()).thenReturn(true);
        when(authProperties.sms()).thenReturn(new SmsProperties(
                "482913", 300, "https://sms.example.com/send",
                "webhook", null, null, null, null, null));

        WeChatPayProperties weChatPayProperties = mock(WeChatPayProperties.class);
        when(weChatPayProperties.enabled()).thenReturn(payEnabled);
        when(weChatPayProperties.isConfigured()).thenReturn(payEnabled && payConfigured);
        when(weChatPayProperties.platformCert()).thenReturn("");
        when(weChatPayProperties.platformCertAutoFetch()).thenReturn(true);

        WeChatMiniAppProperties weChatMiniAppProperties = mock(WeChatMiniAppProperties.class);
        when(weChatMiniAppProperties.enabled()).thenReturn(miniappEnabled);
        when(weChatMiniAppProperties.isConfigured()).thenReturn(miniappEnabled && miniappConfigured);

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
        when(identityVerifyProperties.isConfigured()).thenReturn(identityConfigured);

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
