package com.aicabinet.jiangyi.config;

import com.aicabinet.common.security.InternalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AU-075：将邑 gateway 此前零启动校验——prod 栈可拿 dev 默认 key、空 CIDR（不限来源 IP）、
 * 占位/缺失的 JIANGYI_JWT_SECRET 静默上线。本测试钉死严格 profile 下的四项拒绝 + dev 不误杀。
 */
class ProductionStartupValidatorTest {

    private static final String STRONG_KEY = "prod-internal-key-32bytes-at-least!!";
    private static final String STRONG_JWT = "prod-jiangyi-jwt-secret-32bytes-at-least";

    private ProductionStartupValidator build(String profile, String internalKey, String cidrs, String jwtSecret) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] { profile });
        return new ProductionStartupValidator(environment,
                new InternalApiProperties(internalKey, cidrs, ""), jwtSecret);
    }

    @Test
    void prodRejectsDevDefaultInternalApiKey() {
        assertThrows(IllegalStateException.class,
                () -> build("prod", "dev-internal-key-change-me", "172.16.0.0/12", STRONG_JWT).validate());
    }

    @Test
    void prodRejectsEmptyInternalApiCidr() {
        // 空 CIDR=不限来源 IP：/internal/** 只剩一把 key 裸奔
        assertThrows(IllegalStateException.class,
                () -> build("prod", STRONG_KEY, "", STRONG_JWT).validate());
    }

    @Test
    void prodRejectsMissingJwtSecret() {
        // 运行期 503 fail-closed 保留，但配置缺失必须启动期就炸，不许拖到柜机激活当天
        assertThrows(IllegalStateException.class,
                () -> build("prod", STRONG_KEY, "172.16.0.0/12", "").validate());
    }

    @Test
    void prodRejectsPlaceholderJwtSecret() {
        assertThrows(IllegalStateException.class,
                () -> build("prod", STRONG_KEY, "172.16.0.0/12",
                        "change-me-to-32-bytes-min-secret-0123456789").validate());
    }

    @Test
    void stagingSameGuardsApply() {
        assertThrows(IllegalStateException.class,
                () -> build("staging", STRONG_KEY, "", STRONG_JWT).validate());
    }

    @Test
    void prodWithStrongConfigStarts() {
        assertDoesNotThrow(() -> build("prod", STRONG_KEY, "172.16.0.0/12", STRONG_JWT).validate());
    }

    @Test
    void devProfileNotBlocked() {
        assertDoesNotThrow(() -> build("dev", "dev-internal-key-change-me", "", "").validate());
    }
}
