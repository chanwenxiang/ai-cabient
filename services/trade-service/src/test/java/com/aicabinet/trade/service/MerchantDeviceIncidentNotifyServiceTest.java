package com.aicabinet.trade.service;

import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.MerchantSubscribePref;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantNotifyLogMapper;
import com.aicabinet.trade.mapper.MerchantSubscribePrefMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantDeviceIncidentNotifyServiceTest {

    @Mock private MerchantSubscribePrefMapper subscribePrefRepository;
    @Mock private MerchantNotifyLogMapper notifyLogRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private PermissionService permissionService;
    @Mock private WeChatMiniAppClient weChatMiniAppClient;
    @Mock private WeChatMiniAppProperties weChatMiniAppProperties;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private SystemConfigService systemConfigService;
    @Mock private RedissonClient redissonClient;
    @Mock private RBucket<String> bucket;

    private MerchantDeviceIncidentNotifyService service;

    @BeforeEach
    void setUp() {
        service = new MerchantDeviceIncidentNotifyService(
                subscribePrefRepository, notifyLogRepository, userInfoRepository,
                merchantFeaturePackService, permissionService, weChatMiniAppClient,
                weChatMiniAppProperties, opsAlertDispatcher, systemConfigService, redissonClient);
        when(systemConfigService.getInt(
                SystemConfigService.MERCHANT_INCIDENT_NOTIFY_COOLDOWN_MINUTES, 30)).thenReturn(30);
        when(redissonClient.<String>getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(bucket);
    }

    @Test
    void notifyDeviceOffline_cooldownSkip_doesNotPush() {
        when(bucket.setIfAbsent(eq("1"), any(Duration.class))).thenReturn(false);

        int sent = service.notifyDeviceOffline("CAB-001", "心跳超时");

        assertEquals(0, sent);
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString(), anyMap());
        verify(subscribePrefRepository, never()).findByAlertTypeAndEnabledTrue(anyString());
    }

    @Test
    void notifyDeviceOffline_sendsOpsAndMerchantSubscribe() {
        when(bucket.setIfAbsent(eq("1"), any(Duration.class))).thenReturn(true);
        MerchantSubscribePref pref = new MerchantSubscribePref(900L, "DEVICE_OFFLINE");
        when(subscribePrefRepository.findByAlertTypeAndEnabledTrue("DEVICE_OFFLINE"))
                .thenReturn(List.of(pref));
        when(permissionService.hasPermission(900L, "merchant:alerts:view")).thenReturn(true);
        when(merchantFeaturePackService.allowedDeviceIdsForPack(900L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("CAB-001"));
        UserInfo user = new UserInfo();
        user.setUserId(900L);
        user.setWxOpenId("ox-demo");
        when(userInfoRepository.findById(900L)).thenReturn(Optional.of(user));
        when(notifyLogRepository.findFirstByUserIdAndDigestAndSentAtAfter(eq(900L), anyString(), any()))
                .thenReturn(Optional.empty());
        when(weChatMiniAppProperties.subscribeTemplateId()).thenReturn("tpl-1");
        when(weChatMiniAppProperties.resolveNotifyPage()).thenReturn("pages/alerts/alerts");
        when(weChatMiniAppClient.sendSubscribeMessage(eq("ox-demo"), eq("tpl-1"), anyString(), anyMap()))
                .thenReturn(true);

        int sent = service.notifyDeviceOffline("CAB-001", "连续 2 分钟未收到心跳");

        assertEquals(1, sent);
        verify(opsAlertDispatcher).send(eq("DEVICE_OFFLINE"), anyString(), anyString(), anyMap());
        verify(notifyLogRepository).save(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(weChatMiniAppClient).sendSubscribeMessage(eq("ox-demo"), eq("tpl-1"), anyString(), data.capture());
        assertEquals("柜机离线", data.getValue().get("thing1"));
    }

    @Test
    void notifySalesLocked_skipsUserWithoutDeviceScope() {
        when(bucket.setIfAbsent(eq("1"), any(Duration.class))).thenReturn(true);
        MerchantSubscribePref pref = new MerchantSubscribePref(901L, "SALES_LOCKED");
        when(subscribePrefRepository.findByAlertTypeAndEnabledTrue("SALES_LOCKED"))
                .thenReturn(List.of(pref));
        when(permissionService.hasPermission(901L, "merchant:alerts:view")).thenReturn(true);
        when(merchantFeaturePackService.allowedDeviceIdsForPack(901L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("CAB-OTHER"));

        int sent = service.notifySalesLocked("CAB-001", "人工停售");

        assertEquals(0, sent);
        verify(opsAlertDispatcher).send(eq("SALES_LOCKED"), anyString(), anyString(), anyMap());
        verify(weChatMiniAppClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap());
    }
}
