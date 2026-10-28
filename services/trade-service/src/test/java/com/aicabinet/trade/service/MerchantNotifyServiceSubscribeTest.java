package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSubscribeRequest;
import com.aicabinet.trade.domain.MerchantSubscribePref;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.MerchantSubscribePrefMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉住 2026-10-28 修复：merchant_subscribe_pref 是 (user_id, alert_type) 联合主键、无单列自增主键，
 * MyBatis-Plus 的 BaseMapper.selectById/save/updateById 依赖 @TableId 单主键会抛
 * BindingException「Invalid bound statement (not found)…selectById」。
 * doUpdateSubscribe 必须只用 insert / update(wrapper) / selectCount(wrapper)，绝不碰
 * selectById / save / findById。
 */
@ExtendWith(MockitoExtension.class)
class MerchantNotifyServiceSubscribeTest {

    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private PermissionService permissionService;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private MerchantSubscribePrefMapper subscribePrefRepository;
    @Mock private DistributedLockService distributedLockService;

    private MerchantNotifyService service;

    @BeforeEach
    void setUp() {
        service = new MerchantNotifyService(
                merchantPortalGuard, permissionService, null, null, null,
                userInfoRepository, subscribePrefRepository, null, null, null, null,
                distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void updateSubscribe_rebuildsPrefsViaWrapper_notSelectById() {
        Long userId = 900L;
        when(distributedLockService.tryLock(
                MerchantNotifyService.merchantNotifySubscribeLockKey(userId), 60L, 5L))
                .thenReturn(true);
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        when(userInfoRepository.findById(userId)).thenReturn(Optional.of(user));
        // 该用户当前无任何偏好
        when(subscribePrefRepository.findByIdUserIdAndEnabledTrue(userId)).thenReturn(List.of());
        // selectCount：本次勾选的两个类型均不存在 → 走 insert 分支
        when(subscribePrefRepository.selectCount(any())).thenReturn(0L);

        service.updateSubscribe(userId, new MerchantSubscribeRequest(List.of("DEVICE_OFFLINE", "LOW_STOCK")));

        // 全量关闭既有偏好（update + set enabled=false）
        verify(subscribePrefRepository).update(any(), any());
        // 两个类型各自 insert（绝不用 save/selectById）
        verify(subscribePrefRepository, org.mockito.Mockito.times(2)).insert(any(MerchantSubscribePref.class));
        verify(subscribePrefRepository, never()).selectById(any());
        verify(subscribePrefRepository, never()).findById(any());
    }

    @Test
    void updateSubscribe_filtersUnknownTypes() {
        Long userId = 901L;
        when(distributedLockService.tryLock(
                MerchantNotifyService.merchantNotifySubscribeLockKey(userId), 60L, 5L))
                .thenReturn(true);
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        when(userInfoRepository.findById(userId)).thenReturn(Optional.of(user));
        when(subscribePrefRepository.findByIdUserIdAndEnabledTrue(userId)).thenReturn(List.of());

        service.updateSubscribe(userId, new MerchantSubscribeRequest(List.of("NOT_A_REAL_TYPE")));

        // 未知类型被过滤，不 insert 任何行
        verify(subscribePrefRepository, never()).insert(any());
    }
}
