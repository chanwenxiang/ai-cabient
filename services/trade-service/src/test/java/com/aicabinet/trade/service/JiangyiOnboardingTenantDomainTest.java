package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiMerchantClient;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Mock private JiangyiDeviceMapper jiangyiDeviceMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private JiangyiMerchantClient jiangyiMerchantClient;
    @Mock private JiangyiDeviceDirectory jiangyiDeviceDirectory;
    @Mock private SystemConfigService systemConfigService;

    private JiangyiOnboardingService service(String secret) {
        return new JiangyiOnboardingService(jiangyiDeviceMapper, deviceInfoMapper,
                jiangyiMerchantClient, jiangyiDeviceDirectory, systemConfigService,
                TENANT_ID, secret);
    }

    @Test
    void rejectsDomainWithoutJiangyiApiTail() {
        // 漏尾段 = 设备切到一个我方 nginx 不路由的地址，比不切更难查，必须 fail-fast
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service("s").setTenantDomain("https://gate.example.com", null));
        assertTrue(ex.getMessage().contains("/jiangyi/api"));
        verify(jiangyiMerchantClient, never()).setDomain(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void requiresTenantCredentials() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service("").setTenantDomain("https://gate.example.com/jiangyi/api", null));
        assertTrue(ex.getMessage().contains("JIANGYI_TENANT_ID"));
    }

    @Test
    void derivesSocketUrlAndPersistsLatestValues() {
        JiangyiOnboardingService.TenantDomainView view = service("secret")
                .setTenantDomain("https://gate.example.com/jiangyi/api/", "");

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
        JiangyiOnboardingService.TenantDomainView view = service("secret")
                .setTenantDomain("https://gate.example.com/jiangyi/api", "wss://other.example.com/websocket/device/");

        assertEquals("wss://other.example.com/websocket/device/", view.socketUrl());
        verify(jiangyiMerchantClient).setDomain(eq(TENANT_ID), eq("secret"),
                eq("https://gate.example.com/jiangyi/api"), eq("wss://other.example.com/websocket/device/"));
    }
}
