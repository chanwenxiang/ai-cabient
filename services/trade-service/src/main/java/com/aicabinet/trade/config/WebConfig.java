package com.aicabinet.trade.config;

import com.aicabinet.common.security.InternalApiAuthInterceptor;
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

        // 默认拒绝：/api/v2/** 一律要求登录，仅显式放行公开/回调端点。
        // 新增控制器无需改这里即可获得鉴权；若确属公开接口，才追加到 excludePathPatterns。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v2/**")
                .excludePathPatterns(
                        // 登录/刷新/验证码
                        "/api/v2/auth/**",
                        // 支付平台真实回调（各自做签名验签）。勿用 wechat/**、alipay/**：
                        // 否则 /notify/mock/** 也会裸奔，可任意 userId 模拟入账。
                        "/api/v2/payment/wechat/notify",
                        "/api/v2/payment/alipay/notify",
                        // 消费者公开公告
                        "/api/v2/announcements/**",
                        // 商品图等 <img> 直链
                        "/api/v2/media/**",
                        // 消费者公开配置
                        "/api/v2/public/**",
                        // 营销活动/轮播（游客可见；领券 /claim 仍需登录）
                        "/api/v2/marketing/banners",
                        "/api/v2/marketing/campaigns/active");
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
