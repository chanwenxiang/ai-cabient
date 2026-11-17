package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiMerchantClient;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户级 setDomain（2026-10-20，系统参数设置「将邑接入」）。
 *
 * <p>锁三件事：① domain 必须带 /jiangyi/api 尾段（我方 nginx 只路由 /jiangyi/*，
 * 漏尾段=设备切过来全 404）；② socketUrl 留空按 domain 自动推导 wss://host/websocket/device/；
 * ③ 成功后最近下发值落 SystemConfig 三键（参数设置页回显依据）。厂商调用是租户级共享写，
 * 与单设备 bind 的差异（不带 deviceId、不落设备行）由 bindDomain 自身测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class JiangyiOnboardingTenantDomainTest {

    private static final long TENANT_ID = 426L;
    /** MA-315：租户级 setDomain 只允许平台全局账号；happy path 一律用这个账号。 */
    private static final long GLOBAL_OPERATOR = 100000006L;
    /** 域受限运营（role 2 持 ops:device:edit 但无全局范围）——必须被挡在厂商调用之前。 */
    private static final long SCOPED_OPERATOR = 100000009L;

    @Mock private JiangyiDeviceMapper jiangyiDeviceMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private JiangyiMerchantClient jiangyiMerchantClient;
    @Mock private JiangyiDeviceDirectory jiangyiDeviceDirectory;
    @Mock private SystemConfigService systemConfigService;
    @Mock private MerchantScopeService merchantScopeService;

    private JiangyiOnboardingService service(String secret) {
        return new JiangyiOnboardingService(jiangyiDeviceMapper, deviceInfoMapper,
                jiangyiMerchantClient, jiangyiDeviceDirectory, systemConfigService,
                org.mockito.Mockito.mock(com.aicabinet.trade.service.AdminAuditService.class),
                merchantScopeService, TENANT_ID, secret);
    }

    /** 全局判据（严格桩：只在会走到厂商调用的用例里声明）。 */
    private void allowGlobal() {
        when(merchantScopeService.isGlobalScope(GLOBAL_OPERATOR)).thenReturn(true);
    }

    @Test
    void rejectsDomainWithoutJiangyiApiTail() {
        allowGlobal();
        // 漏尾段 = 设备切到一个我方 nginx 不路由的地址，比不切更难查，必须 fail-fast
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service("s").setTenantDomain(GLOBAL_OPERATOR, "https://gate.example.com", null));
        assertTrue(ex.getMessage().contains("/jiangyi/api"));
        verify(jiangyiMerchantClient, never()).setDomain(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void requiresTenantCredentials() {
        allowGlobal();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service("").setTenantDomain(GLOBAL_OPERATOR, "https://gate.example.com/jiangyi/api", null));
        assertTrue(ex.getMessage().contains("JIANGYI_TENANT_ID"));
    }

    @Test
    void derivesSocketUrlAndPersistsLatestValues() {
        allowGlobal();
        JiangyiOnboardingService.TenantDomainView view = service("secret")
                .setTenantDomain(GLOBAL_OPERATOR, "https://gate.example.com/jiangyi/api/", "");

        // 尾斜杠被归一化；socketUrl 缺省=推导，且只取 scheme+authority
        assertEquals("https://gate.example.com/jiangyi/api", view.domain());
        assertEquals("wss://gate.example.com/websocket/device/", view.socketUrl());

        ArgumentCaptor<String> domain = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> socketUrl = ArgumentCaptor.forClass(String.class);
        verify(jiangyiMerchantClient).setDomain(eq(TENANT_ID), eq("secret"),
                domain.capture(), socketUrl.capture());
        assertEquals("https://gate.example.com/jiangyi/api", domain.getValue());
        assertEquals("wss://gate.example.com/websocket/device/", socketUrl.getValue());

        verify(systemConfigService).upsert(eq("jiangyi.tenant.domain"), eq("https://gate.example.com/jiangyi/api"), anyString());
        verify(systemConfigService).upsert(eq("jiangyi.tenant.socketUrl"), eq("wss://gate.example.com/websocket/device/"), anyString());
        verify(systemConfigService).upsert(eq("jiangyi.tenant.pushedAt"), anyString(), anyString());
        assertTrue(view.pushedAt() != null && !view.pushedAt().isBlank());
    }

    @Test
    void keepsProvidedSocketUrlWhenGiven() {
        allowGlobal();
        JiangyiOnboardingService.TenantDomainView view = service("secret")
                .setTenantDomain(GLOBAL_OPERATOR, "https://gate.example.com/jiangyi/api", "wss://other.example.com/websocket/device/");

        assertEquals("wss://other.example.com/websocket/device/", view.socketUrl());
        verify(jiangyiMerchantClient).setDomain(eq(TENANT_ID), eq("secret"),
                eq("https://gate.example.com/jiangyi/api"), eq("wss://other.example.com/websocket/device/"));
    }

    /**
     * MA-315（2026-11-17 对抗验证轮）：端点只挂 {@code ops:device:edit}，而 role 2（运营）确实持有该码
     * （V8 种子 + V118 回填），且 {@code OpsDeviceScopeInterceptor} 只拦路径带 {@code {deviceId}} 的端点
     * ⇒ 修前域受限运营可把**全平台**将邑柜的上报/WS 目标切到任意域名。判据：只允许平台全局账号。
     */
    @Test
    @DisplayName("🔴 MA-315 域受限运营不得改租户级 setDomain（厂商调用与 SystemConfig 都不许动）")
    void scopedOperator_isRejectedBeforeVendorCall() {
        when(merchantScopeService.isGlobalScope(SCOPED_OPERATOR)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service("secret").setTenantDomain(SCOPED_OPERATOR,
                        "https://evil.example.com/jiangyi/api", null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(jiangyiMerchantClient, never()).setDomain(anyLong(), anyString(), anyString(), anyString());
        verify(systemConfigService, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 MA-315 操作者为 null 直接拒：fail-closed，与拦截器「拿不到身份不放行」同口径")
    void nullOperator_isRejected() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service("secret").setTenantDomain(null, "https://gate.example.com/jiangyi/api", null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(merchantScopeService, never()).isGlobalScope(any());
        verify(jiangyiMerchantClient, never()).setDomain(anyLong(), anyString(), anyString(), anyString());
    }
}
