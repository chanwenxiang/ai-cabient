package com.aicabinet.trade.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * 钉住 WebConfig CORS 消费行为（2026-10-13 生产首启修复配套）：
 * application-prod.yml 以 {@code ${CORS_ORIGIN:}} 注入，留空时列表含一个空串占位——
 * 空串不是合法 origin，必须过滤；过滤后为空等价于「同源部署，不放宽跨域」。
 */
class WebConfigCorsTest {

    private WebConfig buildWebConfig(List<String> origins) {
        CorsProperties corsProperties = mock(CorsProperties.class);
        when(corsProperties.allowedOrigins()).thenReturn(origins);
        return new WebConfig(
                mock(com.aicabinet.trade.auth.AuthInterceptor.class),
                mock(com.aicabinet.common.security.InternalApiAuthInterceptor.class),
                mock(ApiVersionInterceptor.class),
                corsProperties,
                mock(com.aicabinet.trade.service.DeviceHttpCredentialService.class));
    }

    /** CorsRegistration 链式 API：RETURNS_SELF 自返，避免 varargs stub 匹配问题。 */
    private CorsRegistration selfReturningRegistration() {
        return mock(CorsRegistration.class, org.mockito.Mockito.RETURNS_SELF);
    }

    @Test
    void blankOriginOnlyListRegistersNoCorsMapping() {
        CorsRegistry registry = mock(CorsRegistry.class);
        buildWebConfig(List.of("")).addCorsMappings(registry);
        verify(registry, never()).addMapping(anyString());
    }

    @Test
    void blankEntriesFilteredRealOriginsKept() {
        CorsRegistry registry = mock(CorsRegistry.class);
        CorsRegistration registration = selfReturningRegistration();
        when(registry.addMapping("/api/**")).thenReturn(registration);

        buildWebConfig(List.of("https://kuangfuquan.com", "")).addCorsMappings(registry);

        verify(registry).addMapping("/api/**");
        verify(registration).allowedOrigins("https://kuangfuquan.com");
        verify(registration).allowCredentials(true);
    }

    @Test
    void realOriginListRegistersCorsMapping() {
        CorsRegistry registry = mock(CorsRegistry.class);
        CorsRegistration registration = selfReturningRegistration();
        when(registry.addMapping("/api/**")).thenReturn(registration);

        buildWebConfig(List.of("https://a.example.com", "https://b.example.com")).addCorsMappings(registry);

        verify(registration).allowedOrigins("https://a.example.com", "https://b.example.com");
    }
}
