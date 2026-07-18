package com.aicabinet.trade.config;

import com.aicabinet.trade.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final InternalApiAuthInterceptor internalApiAuthInterceptor;
    private final CorsProperties corsProperties;

    public WebConfig(AuthInterceptor authInterceptor,
                     InternalApiAuthInterceptor internalApiAuthInterceptor,
                     CorsProperties corsProperties) {
        this.authInterceptor = authInterceptor;
        this.internalApiAuthInterceptor = internalApiAuthInterceptor;
        this.corsProperties = corsProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**");

        registry.addInterceptor(authInterceptor)
                .addPathPatterns(
                        "/api/v2/sessions/**",
                        "/api/v2/orders/**",
                        "/api/v2/account/**",
                        "/api/v2/payment/recharge/**",
                        "/api/v2/payment/recharges/**",
                        "/api/v2/devices/**",
                        "/api/v2/disputes/**",
                        "/api/v2/feedback/**",
                        "/api/v2/ops/**",
                        "/api/v2/coupons/**",
                        "/api/v2/member/**",
                        "/api/v2/merchant/**",
                        "/api/v2/dicts/**",
                        "/api/v2/marketing/campaigns/*/claim")
                .excludePathPatterns(
                        "/api/v2/auth/**",
                        "/api/v2/payment/wechat/**",
                        "/api/v2/payment/alipay/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsProperties.allowedOrigins() == null || corsProperties.allowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
